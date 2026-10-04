package au.com.kit.fitnesslogsync
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            textSize = 18f; setPadding(30, 60, 30, 30)
            text = "Fitness Log Sync reads steps directly from Samsung Health. Workout sessions, calories, weight, body fat, height and basal metabolic rate are read through Health Connect. It sends them only to your configured Google Sheet. Background access enables automatic uploads. History access enables catch-up and older measurement reconciliation. Notifications warn about unresolved problems. Sleep and distance are not read. The app does not modify Health Connect. Connection credentials are encrypted on this phone. Uninstalling removes local credentials and queued uploads; your Google Sheet remains."
        })
    }
}
