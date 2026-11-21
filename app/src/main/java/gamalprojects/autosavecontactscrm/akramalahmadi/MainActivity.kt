package gamalprojects.autosavecontactscrm.akramalahmadi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModelProvider
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
                    AutoSaveCrmApp(viewModel = crmViewModel)
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
