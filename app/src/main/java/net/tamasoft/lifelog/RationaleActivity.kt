package net.tamasoft.lifelog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Health Connect asks an app to say why it reads, before it shows its dialog. */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(24.dp)) {
                        Text("Why Lifelog reads Health Connect", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Lifelog reads the health records you allow (steps, heart rate, sleep, " +
                                "workouts, meditation and the rest) and sends them to the one server " +
                                "you paired this phone with, your own. It writes nothing to Health " +
                                "Connect and sends nothing anywhere else. Turn the Health module off, " +
                                "or remove the permissions here, and it stops.",
                            Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}
