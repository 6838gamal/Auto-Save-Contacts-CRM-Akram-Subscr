package gamalprojects.autosavecontactscrm.akramalahmadi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import gamalprojects.autosavecontactscrm.akramalahmadi.network.DebugConsoleActivity
import gamalprojects.autosavecontactscrm.akramalahmadi.presentation.AutoSaveCrmApp
import gamalprojects.autosavecontactscrm.akramalahmadi.presentation.CrmViewModel

class MainActivity : ComponentActivity() {

    private lateinit var crmViewModel: CrmViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        crmViewModel = ViewModelProvider(this)[CrmViewModel::class.java]

        setContent {
            MaterialTheme {
                Surface {
                    Box {
                        AutoSaveCrmApp(viewModel = crmViewModel)
                        DebugFab(
                            onClick = {
                                startActivity(Intent(this@MainActivity, DebugConsoleActivity::class.java))
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::crmViewModel.isInitialized) {
            crmViewModel.checkAllPermissionStates()
        }
    }
}

/**
 * زر عائم صغير في الزاوية لفتح شاشة التشخيص.
 * احذفه قبل نشر التطبيق في الإنتاج.
 */
@Composable
private fun DebugFab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(12.dp)
            .size(44.dp)
            .background(Color(0xCCB00020), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.BugReport,
            contentDescription = "فتح سجل التشخيص",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}
