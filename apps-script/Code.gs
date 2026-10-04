/** Fitness Log Sync v1. Dedicated project: do not paste into the old Second Brain script. */
const VERSION = 1;
const SCHEMAS = {
  'Daily Activity': ['date','metric','value','unit','data_status','source','observed_at','received_at'],
  'Workouts': ['record_id','start_at','end_at','start_date','exercise_type','title','elapsed_minutes','source','modified_at','received_at'],
  'Measurements': ['record_id','measured_at','metric','value','unit','source','modified_at','deleted','received_at'],
  'Sync Status': ['key','value','updated_at']
};
const DAILY_METRICS = {steps:['steps'], exercise:['exercise_elapsed_minutes','exercise_sessions'], calories:['calories']};
const MEASUREMENT_UNITS = {weight:'kg',body_fat:'%',height:'cm',basal_metabolic_rate:'kcal/day'};

/** Run once in a NEW standalone Apps Script project. Re-running reuses the same sheet and key. */
function setupFitnessLog() {
  const lock = LockService.getScriptLock(); lock.waitLock(30000);
  try {
    const properties = PropertiesService.getScriptProperties();
    let id = properties.getProperty('FITNESS_SPREADSHEET_ID');
    let book;
    if (id) book = SpreadsheetApp.openById(id);
    else {
      book = SpreadsheetApp.create('Fitness Log');
      book.setSpreadsheetTimeZone('Australia/Sydney');
      book.setSpreadsheetLocale('en_AU');
      properties.setProperty('FITNESS_SPREADSHEET_ID', book.getId());
      book.getSheets()[0].setName('Daily Activity');
    }
    if (!properties.getProperty('FITNESS_SHARED_SECRET'))
      properties.setProperty('FITNESS_SHARED_SECRET', Utilities.getUuid().replace(/-/g,'') + Utilities.getUuid().replace(/-/g,''));
    Object.keys(SCHEMAS).forEach(name => {
      const sheet = book.getSheetByName(name) || book.insertSheet(name);
      if (!sheet.getLastRow()) sheet.getRange(1,1,1,SCHEMAS[name].length).setValues([SCHEMAS[name]]);
      verifyHeaders_(sheet, SCHEMAS[name]);
      sheet.setFrozenRows(1);
      sheet.getRange(1,1,1,SCHEMAS[name].length).setBackground('#256B5D').setFontColor('#FFFFFF').setFontWeight('bold');
      sheet.setColumnWidths(1, SCHEMAS[name].length, 160);
      if (name === 'Sync Status') sheet.setColumnWidth(2, 460);
    });
    putStatus_(book, {service_version:VERSION, setup_state:'awaiting_phone', timezone:'Australia/Sydney', configured_at:new Date().toISOString()});
    console.log('Sheet: ' + book.getUrl());
    console.log('Connection key: ' + properties.getProperty('FITNESS_SHARED_SECRET'));
    return book.getUrl();
  } finally { lock.releaseLock(); }
}

/** For recovery only: rotates the key. Paste the new key into the phone after running. */
function rotateConnectionKey() {
  const secret = Utilities.getUuid().replace(/-/g,'') + Utilities.getUuid().replace(/-/g,'');
  PropertiesService.getScriptProperties().setProperty('FITNESS_SHARED_SECRET', secret);
  console.log('New connection key: ' + secret);
}

function doGet() { return json_({ok:true, service:'Fitness Log Sync', version:VERSION}); }
function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const raw = e && e.postData && e.postData.contents || '';
    if (raw.length > 4000000) fail_('payload','Request is too large.',false);
    const body = JSON.parse(raw || '{}');
    const props = PropertiesService.getScriptProperties();
    const expected = props.getProperty('FITNESS_SHARED_SECRET');
    if (!expected || typeof body.token !== 'string' || body.token !== expected) fail_('authentication','The connection key was rejected. Check the key saved in the app.',false);
    const p = body.payload;
    if (!p || !/^[a-zA-Z0-9-]{20,80}$/.test(p.request_id || '')) fail_('payload','Missing request identifier.',false);
    lock.waitLock(30000);
    const id = props.getProperty('FITNESS_SPREADSHEET_ID');
    if (!id) fail_('setup','Run setupFitnessLog in the script editor first.',false);
    const book = SpreadsheetApp.openById(id);
    let count = 0;
    if (p.action === 'daily') count = applyDaily_(book,p);
    else if (p.action === 'measurements') count = applyMeasurements_(book,p);
    else if (p.action === 'status') {
      if (!p.status || typeof p.status !== 'object' || Array.isArray(p.status)) fail_('payload','Missing sync status.',false);
      const status = {last_attempt_at:text_(p.last_attempt_at,80), last_error:text_(p.last_error,1200), pending_uploads:number_(p.pending_uploads), setup_state:'active'};
      const allowed = ['history_access','background_access','notifications_enabled','automatic_enabled','history_start','history_gap'];
      allowed.concat(Object.keys(DAILY_METRICS),Object.keys(MEASUREMENT_UNITS)).forEach(k => {
        if (Object.prototype.hasOwnProperty.call(p.status,k)) status[k] = String(p.status[k]).slice(0,500);
      });
      putStatus_(book,status);
    } else fail_('version','Unknown request action. Check that app and script versions match.',false);
    putStatus_(book,{last_contact_at:new Date().toISOString(), service_version:VERSION});
    SpreadsheetApp.flush();
    return json_({ok:true, request_id:p.request_id, verified:true, rows_written:count, spreadsheet_id:book.getId(), spreadsheet_url:book.getUrl()});
  } catch (error) {
    // No payloads or credentials enter logs or error responses.
    const message = error && error.message ? error.message : 'Google upload failed.';
    const temporary = /quota|too many|rate limit|timed out|timeout|try again|service unavailable|internal error/i.test(message);
    return json_({ok:false, code:error.code || 'google_service', error:message.slice(0,1200), retryable:error.retryable === undefined ? temporary : error.retryable});
  } finally { try { lock.releaseLock(); } catch (_) {} }
}

function applyDaily_(book,p) {
  const metrics = DAILY_METRICS[p.category];
  if (!metrics) fail_('payload','Unknown daily category.',false);
  const start = date_(p.start_date), end = date_(p.end_date);
  const through = date_(p.complete_through), coverage = date_(p.coverage_start);
  if (through > end) fail_('payload','Completion date exceeds the uploaded range.',false);
  if (end < start || (Date.parse(end)-Date.parse(start))/86400000 > 6) fail_('payload','Daily upload must cover at most seven dates.',false);
  const rows = array_(p.rows), workouts = array_(p.workouts);
  const seen = new Set();
  const incoming = rows.map(r => {
    const date = date_(r.date);
    if (date < start || date > end || metrics.indexOf(r.metric) < 0) fail_('payload','Daily row is outside the declared category or dates.',false);
    const key = date + '|' + r.metric;
    if (seen.has(key)) fail_('payload','Duplicate daily row.',false);
    seen.add(key);
    const state = r.data_status;
    if (state !== 'no_data' && state !== 'value') fail_('payload','Invalid data status.',false);
    if (state === 'no_data' && r.value !== null) fail_('payload','Unavailable data must have a null value.',false);
    return [date, r.metric, state === 'no_data' ? '' : number_(r.value), text_(r.unit,30),state,source_(r.source),instant_(r.observed_at),new Date().toISOString()];
  });
  const dates = [];
  for (let d = new Date(start+'T00:00:00Z'); d.toISOString().slice(0,10) <= end; d.setUTCDate(d.getUTCDate()+1)) dates.push(d.toISOString().slice(0,10));
  dates.forEach(d => metrics.forEach(m => { if (!seen.has(d+'|'+m)) fail_('payload','Incomplete daily snapshot. Existing data was preserved.',false); }));
  if (p.category !== 'exercise' && workouts.length) fail_('payload','Unexpected workouts in this category.',false);
  const workoutIds = new Set();
  const workoutRows = workouts.map(r => {
    const recordId = text_(r.record_id,200);
    if (!recordId || workoutIds.has(recordId)) fail_('payload','Duplicate or missing workout identifier.',false);
    workoutIds.add(recordId);
    const day = date_(r.start_date);
    if (day < start || day > end) fail_('payload','Workout is outside the declared dates.',false);
    const from = instant_(r.start_at), to = instant_(r.end_at);
    if (Date.parse(to) < Date.parse(from)) fail_('payload','Workout ends before it begins.',false);
    return [recordId,from,to,day,number_(r.exercise_type),text_(r.title,500),number_(r.elapsed_minutes),source_(r.source),instant_(r.modified_at),new Date().toISOString()];
  });
  // Validate the entire request, including secondary tables, before the first write.
  const daily = readTable_(book,'Daily Activity');
  const work = p.category === 'exercise' ? readTable_(book,'Workouts') : null;
  const next = daily.filter(r => !(r[0] >= start && r[0] <= end && metrics.indexOf(r[1]) >= 0)).concat(incoming);
  writeTable_(book,'Daily Activity',next);
  verifyRows_(book,'Daily Activity',next, r => r[0]+'|'+r[1]);
  if (work) {
    const nextWork = work.filter(r => !(r[3] >= start && r[3] <= end)).concat(workoutRows);
    writeTable_(book,'Workouts',nextWork);
    verifyRows_(book,'Workouts',nextWork,r => r[0]);
  }
  const status = {last_data_upload_at:new Date().toISOString()};
  const old = readStatus_(book);
  status['complete_through_'+p.category] = old['complete_through_'+p.category] > through ? old['complete_through_'+p.category] : through;
  // coverage_start indicates declared readable history, not proof all older rows are present.
  status['coverage_start_'+p.category] = coverage;
  status['confirmed_at_'+p.category] = new Date().toISOString();
  putStatus_(book,status);
  return incoming.length + workoutRows.length;
}

function applyMeasurements_(book,p) {
  const unit = MEASUREMENT_UNITS[p.category];
  if (!unit || ['snapshot','changes'].indexOf(p.mode) < 0) fail_('payload','Unknown measurement category or mode.',false);
  const seen = new Set();
  const incoming = array_(p.rows).map(r => {
    const id = text_(r.record_id,250);
    if (id.indexOf(p.category+':') !== 0 || seen.has(id) || r.metric !== p.category || r.unit !== unit) fail_('payload','Invalid or duplicate measurement.',false);
    seen.add(id);
    return [id,instant_(r.measured_at),p.category,number_(r.value),unit,source_(r.source),instant_(r.modified_at),false,new Date().toISOString()];
  });
  const deletes = array_(p.deleted_ids).map(id => {
    if (typeof id !== 'string' || id.indexOf(p.category+':') !== 0) fail_('payload','Invalid deletion identifier.',false);
    if (seen.has(id)) fail_('payload','A measurement cannot be inserted and deleted in one request.',false);
    return id;
  });
  let from, to;
  if (p.mode === 'snapshot') {
    from = instant_(p.start_at); to = instant_(p.end_at);
    if (Date.parse(to) <= Date.parse(from)) fail_('payload','Invalid snapshot range.',false);
    incoming.forEach(r => { if (Date.parse(r[1]) < Date.parse(from) || Date.parse(r[1]) >= Date.parse(to)) fail_('payload','Measurement is outside the readable snapshot range.',false); });
  }
  const table = readTable_(book,'Measurements');
  const map = new Map(table.map(r => [r[0],r]));
  if (p.mode === 'snapshot') map.forEach((r,id) => {
    // Only reconcile records inside the successfully read window. Older history is preserved.
    if (r[2] === p.category && Date.parse(r[1]) >= Date.parse(from) && Date.parse(r[1]) < Date.parse(to) && !seen.has(id))
      map.set(id,r.slice(0,7).concat([true,new Date().toISOString()]));
  });
  incoming.forEach(r => map.set(r[0],r));
  deletes.forEach(id => { const row = map.get(id); if (row) map.set(id,row.slice(0,7).concat([true,new Date().toISOString()])); });
  writeTable_(book,'Measurements',Array.from(map.values()));
  verifyRows_(book,'Measurements',Array.from(map.values()),r => r[0]);
  const saved = readTable_(book,'Measurements');
  deletes.forEach(id => { const row = saved.find(r => r[0] === id); if (row && row[7] !== true) fail_('verification','Measurement deletion could not be verified.',true); });
  putStatus_(book,{['confirmed_at_'+p.category]:new Date().toISOString(),last_data_upload_at:new Date().toISOString()});
  return incoming.length + deletes.length;
}

function verifyHeaders_(sheet,headers) {
  const actual = sheet.getRange(1,1,1,headers.length).getDisplayValues()[0];
  if (actual.join('|') !== headers.join('|')) fail_('schema','The '+sheet.getName()+' headers changed. Restore the expected headers before retrying.',false);
}
function readTable_(book,name) {
  const sheet = book.getSheetByName(name);
  if (!sheet) fail_('schema','Missing tab: '+name+'. Run setupFitnessLog to restore it.',false);
  verifyHeaders_(sheet,SCHEMAS[name]);
  if (sheet.getLastRow() <= 1) return [];
  return sheet.getRange(2,1,sheet.getLastRow()-1,SCHEMAS[name].length).getValues().filter(r => r[0] !== '');
}
function literal_(value) { return typeof value === 'string' && /^[=+@-]/.test(value) ? "'"+value : value; }
function writeTable_(book,name,rows) {
  const sheet = book.getSheetByName(name), width = SCHEMAS[name].length;
  verifyHeaders_(sheet,SCHEMAS[name]);
  const oldLength = Math.max(0,sheet.getLastRow()-1);
  const required = rows.length+1;
  if (required > sheet.getMaxRows()) sheet.insertRowsAfter(sheet.getMaxRows(),required-sheet.getMaxRows());
  if (rows.length) sheet.getRange(2,1,rows.length,width).setValues(rows.map(r => r.map(literal_)));
  if (oldLength > rows.length) sheet.getRange(rows.length+2,1,oldLength-rows.length,width).clearContent();
  SpreadsheetApp.flush();
}
function comparable_(value) {
  // Google may return a Date if a user changed cell formatting. That is a schema/data mismatch, not a success.
  return typeof value === 'string' && value.charAt(0) === "'" && /^[=+@-]/.test(value.slice(1)) ? value.slice(1) : value;
}
function verifyRows_(book,name,expected,key) {
  const saved = new Map(readTable_(book,name).map(r => [key(r),r]));
  expected.forEach(row => {
    const actual = saved.get(key(row));
    if (!actual || actual.some((v,i) => comparable_(v) !== comparable_(row[i]))) fail_('verification','Stored '+name+' data did not match the upload. It will be retried.',true);
  });
}
function readStatus_(book) { return Object.fromEntries(readTable_(book,'Sync Status').map(r => [r[0],r[1]])); }
function putStatus_(book,values) {
  const rows = readTable_(book,'Sync Status'), map = new Map(rows.map(r => [r[0],r]));
  Object.keys(values).forEach(key => map.set(key,[key,String(values[key]),new Date().toISOString()]));
  const updated = Array.from(map.values());
  writeTable_(book,'Sync Status',updated);
  verifyRows_(book,'Sync Status',updated,r => r[0]);
}
function fail_(code,message,retryable) { const e = new Error(message); e.code=code; e.retryable=retryable; throw e; }
function text_(value,max) { if (typeof value !== 'string' || value.length > max) fail_('payload','Invalid text field.',false); return value; }
function number_(value) { if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) fail_('payload','Invalid numeric value.',false); return value; }
function array_(value) { if (!Array.isArray(value) || value.length > 5000) fail_('payload','Invalid row collection.',false); return value; }
function source_(value) { if (value !== 'com.sec.android.app.shealth') fail_('payload','Unexpected health data source.',false); return value; }
function date_(value) { if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0,10) !== value) fail_('payload','Invalid date.',false); return value; }
function instant_(value) { if (typeof value !== 'string' || value.length > 80 || !value.includes('T') || !value.endsWith('Z') || !Number.isFinite(Date.parse(value))) fail_('payload','Invalid timestamp.',false); return value; }
function json_(body) { return ContentService.createTextOutput(JSON.stringify(body)).setMimeType(ContentService.MimeType.JSON); }
