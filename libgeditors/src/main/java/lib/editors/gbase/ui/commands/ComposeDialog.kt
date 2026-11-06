package lib.editors.gbase.ui.commands

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Shows a Compose dialog ([content], an AlertDialog) over [activity]; [content] gets how to
 * close it.
 */
fun showComposeDialog(activity: Activity, content: @Composable (dismiss: () -> Unit) -> Unit) {
    val root = activity.findViewById<ViewGroup>(android.R.id.content)
    val view = ComposeView(activity)
    view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    // Not while the dialog handles the click that closes it
    val dismiss = { root.post { root.removeView(view) } }
    view.setContent { EditorTheme { content { dismiss() } } }
    root.addView(view)
}

/** A password typed in, hidden; [error] said under it. */
@Composable
fun PasswordField(value: String, onValue: (String) -> Unit, label: Int, modifier: Modifier = Modifier, error: Int? = null, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = modifier
    )
}
