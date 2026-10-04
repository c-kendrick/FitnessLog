const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname,'../apps-script/Code.gs'),'utf8');

function fixture() {
  const props = new Map(), books = new Map();
  let creates = 0, corruptNextDaily = false;
  class Sheet {
    constructor(name) { this.name=name; this.cells=[]; this.maxRows=1000; }
    getName() { return this.name; }
    setName(name) { this.name=name; return this; }
    getLastRow() { let i=this.cells.length; while(i && !(this.cells[i-1]||[]).some(v=>v!=='' && v!==undefined)) i--; return i; }
    getMaxRows() { return this.maxRows; }
    insertRowsAfter(n,count) { this.maxRows+=count; }
    setFrozenRows() { return this; }
    setColumnWidths() { return this; }
    setColumnWidth() { return this; }
    getRange(row,col,height,width) {
      const self=this;
      const range = {
        getValues() {
          const output=Array.from({length:height},(_,i)=>Array.from({length:width},(_,j)=>self.cells[row+i-1]?.[col+j-1]??''));
          if (corruptNextDaily && self.name==='Daily Activity' && row===2 && height) { corruptNextDaily=false; output[0][2]=999999; }
          return output;
        },
        getDisplayValues() { return this.getValues().map(r=>r.map(v=>String(v))); },
        setValues(values) { values.forEach((r,i)=>{ self.cells[row+i-1] ||= []; r.forEach((v,j)=>self.cells[row+i-1][col+j-1]=v); }); return range; },
        clearContent() { for(let i=0;i<height;i++) for(let j=0;j<width;j++) { self.cells[row+i-1] ||= []; self.cells[row+i-1][col+j-1]=''; } return range; },
        setBackground() { return range; }, setFontColor() { return range; }, setFontWeight() { return range; }
      };
      return range;
    }
  }
  class Book {
    constructor(id) { this.id=id; this.sheets=[new Sheet('Sheet1')]; }
    getId() { return this.id; }
    getUrl() { return 'https://docs.google.com/spreadsheets/d/'+this.id+'/edit'; }
    setSpreadsheetTimeZone() {} setSpreadsheetLocale() {}
    getSheets() { return this.sheets; }
    getSheetByName(name) { return this.sheets.find(s=>s.name===name); }
    insertSheet(name) { const s=new Sheet(name);this.sheets.push(s);return s; }
  }
  const context = vm.createContext({
    console:{log(){}},
    PropertiesService:{getScriptProperties:()=>({getProperty:k=>props.get(k)||null,setProperty:(k,v)=>props.set(k,v)})},
    LockService:{getScriptLock:()=>({waitLock(){},releaseLock(){}})},
    Utilities:{getUuid:()=>crypto.randomUUID()},
    SpreadsheetApp:{create:()=>{ const b=new Book('test-'+(++creates));books.set(b.id,b);return b; },openById:id=>{ if(!books.has(id))throw new Error('No spreadsheet');return books.get(id); },flush(){}},
    ContentService:{MimeType:{JSON:'application/json'},createTextOutput:text=>({text,setMimeType(){return this;}})}
  });
  vm.runInContext(source,context);
  context.setupFitnessLog();
  const book=books.get(props.get('FITNESS_SPREADSHEET_ID'));
  const request = payload => JSON.parse(context.doPost({postData:{contents:JSON.stringify({token:props.get('FITNESS_SHARED_SECRET'),payload:{request_id:crypto.randomUUID(),...payload}})}}).text);
  const table = name=>book.getSheetByName(name).cells.slice(1).filter(r=>r[0]!=='' && r[0]!==undefined);
  const status=()=>Object.fromEntries(table('Sync Status').map(r=>[r[0],r[1]]));
  return {request,table,status,book,context,props,creates:()=>creates,corrupt:()=>{corruptNextDaily=true;}};
}
const origin='com.sec.android.app.shealth';
const daily=(value, date='2026-10-02')=>({action:'daily',category:'steps',start_date:date,end_date:date,complete_through:date,coverage_start:'2026-07-09',workouts:[],rows:[{date,metric:'steps',value,unit:'steps',data_status:value===null?'no_data':'value',source:origin,observed_at:'2026-10-03T00:00:00Z'}]});
const measurement=(id,value,time='2026-10-02T10:00:00Z')=>({record_id:'weight:'+id,measured_at:time,metric:'weight',value,unit:'kg',source:origin,modified_at:'2026-10-03T00:00:00Z'});
const change=rows=>({action:'measurements',category:'weight',mode:'changes',rows,deleted_ids:[]});

test('setup is idempotent and retains spreadsheet identity and secret',()=>{
  const f=fixture(), secret=f.props.get('FITNESS_SHARED_SECRET');
  f.context.setupFitnessLog();assert.equal(f.creates(),1);assert.equal(f.props.get('FITNESS_SHARED_SECRET'),secret);assert.equal(f.book.sheets.length,4);
});
test('duplicate retry updates the same daily row; corrected count is reflected',()=>{
  const f=fixture();assert.equal(f.request(daily(6000)).ok,true);assert.equal(f.request(daily(6000)).ok,true);
  assert.equal(f.table('Daily Activity').length,1);f.request(daily(6400));assert.equal(f.table('Daily Activity')[0][2],6400);
});
test('no-data remains blank while a genuine zero remains numeric zero',()=>{
  const f=fixture();f.request(daily(null));assert.equal(f.table('Daily Activity')[0][2],'');assert.equal(f.table('Daily Activity')[0][4],'no_data');
  f.request(daily(0));assert.equal(f.table('Daily Activity')[0][2],0);assert.equal(f.table('Daily Activity')[0][4],'value');
});
test('out-of-order backfill preserves the latest completion marker',()=>{
  const f=fixture();f.request(daily(100));f.request(daily(200,'2026-09-15'));
  assert.equal(f.status().complete_through_steps,'2026-10-02');assert.equal(f.table('Daily Activity').length,2);
});
test('incomplete snapshots and invalid checkpoints do not overwrite existing data',()=>{
  const f=fixture();f.request(daily(6000));const before=JSON.stringify(f.table('Daily Activity'));
  const missing=daily(4);missing.end_date='2026-10-03';assert.equal(f.request(missing).ok,false);
  const future=daily(4);future.complete_through='2026-10-04';assert.equal(f.request(future).ok,false);
  assert.equal(JSON.stringify(f.table('Daily Activity')),before);
});
test('measurement edits upsert by record ID; deletions retain a tombstone',()=>{
  const f=fixture();f.request(change([measurement('a',90)]));f.request(change([measurement('a',89)]));
  assert.equal(f.table('Measurements').length,1);assert.equal(f.table('Measurements')[0][3],89);
  const deletion=change([]);deletion.deleted_ids=['weight:a'];f.request(deletion);assert.equal(f.table('Measurements')[0][7],true);
  f.request(change([measurement('a',88)]));assert.equal(f.table('Measurements')[0][7],false);
});
test('expired-token reconciliation deletes only inside the successfully read window',()=>{
  const f=fixture();f.request(change([measurement('old',95,'2026-07-10T10:00:00Z'),measurement('recent',90)]));
  f.request({action:'measurements',category:'weight',mode:'snapshot',start_at:'2026-09-01T00:00:00Z',end_at:'2026-10-03T00:00:00Z',rows:[],deleted_ids:[]});
  assert.equal(f.table('Measurements').find(r=>r[0]==='weight:old')[7],false);
  assert.equal(f.table('Measurements').find(r=>r[0]==='weight:recent')[7],true);
});
test('schema changes are rejected without overwriting data',()=>{
  const f=fixture();f.request(daily(6000));f.book.getSheetByName('Daily Activity').cells[0][0]='renamed';
  const result=f.request(daily(7));assert.equal(result.ok,false);assert.equal(result.code,'schema');assert.equal(f.table('Daily Activity')[0][2],6000);
});
test('readback mismatch rejects acknowledgement and retry reconciles safely',()=>{
  const f=fixture();f.corrupt();const result=f.request(daily(6000));
  assert.equal(result.ok,false);assert.equal(result.code,'verification');assert.equal(result.retryable,true);
  assert.equal(f.status().complete_through_steps,undefined);assert.equal(f.request(daily(6000)).ok,true);assert.equal(f.table('Daily Activity').length,1);
});
test('invalid authentication does not write any data',()=>{
  const f=fixture();const before=JSON.stringify(f.book.sheets.map(s=>s.cells));
  const result=JSON.parse(f.context.doPost({postData:{contents:JSON.stringify({token:'wrong',payload:daily(6000)})}}).text);
  assert.equal(result.code,'authentication');assert.equal(JSON.stringify(f.book.sheets.map(s=>s.cells)),before);
});
test('workout deletion and formula-like titles are safe and idempotent',()=>{
  const f=fixture();const date='2026-10-02';const p={action:'daily',category:'exercise',start_date:date,end_date:date,complete_through:date,coverage_start:date,
    rows:[{date,metric:'exercise_elapsed_minutes',value:30,unit:'minutes',data_status:'value',source:origin,observed_at:'2026-10-03T00:00:00Z'},
      {date,metric:'exercise_sessions',value:1,unit:'sessions',data_status:'value',source:origin,observed_at:'2026-10-03T00:00:00Z'}],
    workouts:[{record_id:'workout-a',start_at:'2026-10-02T10:00:00Z',end_at:'2026-10-02T10:30:00Z',start_date:date,exercise_type:56,title:'=SUM(1,2)',elapsed_minutes:30,source:origin,modified_at:'2026-10-03T00:00:00Z'}]};
  assert.equal(f.request(p).ok,true);assert.equal(f.table('Workouts')[0][5],"'=SUM(1,2)");
  p.workouts=[];p.rows[0].value=0;p.rows[1].value=0;assert.equal(f.request(p).ok,true);assert.equal(f.table('Workouts').length,0);
});
test('one category update preserves other categories and status separates contact from data',()=>{
  const f=fixture();f.request(daily(6000));const calories=daily(1800);calories.category='calories';calories.rows[0].metric='calories';calories.rows[0].unit='kcal';
  f.request(calories);assert.equal(f.table('Daily Activity').length,2);
  assert.equal(f.request({action:'status',status:{steps:'ok',notifications_enabled:false},pending_uploads:0,last_attempt_at:'2026-10-03T00:00:00Z',last_error:''}).ok,true);
  assert.equal(f.status().notifications_enabled,'false');assert.ok(f.status().last_contact_at);assert.ok(f.status().last_data_upload_at);
});
