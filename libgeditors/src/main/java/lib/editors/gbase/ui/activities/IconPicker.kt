package lib.editors.gbase.ui.activities

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import lib.editors.gbase.ui.commands.PictureCell
import lib.editors.gbase.ui.commands.SettingsCaption
import lib.editors.gbase.ui.commands.composePanel

/**
 * Pictures to choose one from, in groups (chart types, shapes, SmartArt; see [InsertCatalog]), in
 * a panel (see [PanelHost]). Their names are read by screen readers.
 */
internal class IconPicker(private val context: Context, private val panels: PanelHost) {

    /**
     * Shows [groups] under [title], [selected] highlighted; [onPick] gets the chosen one's value.
     * [pictures]: the icons are pictures in their own colors (SmartArt), shown larger. After a
     * choice, back to the panel it was opened from ([onBack]), if any.
     */
    fun <T> show(
        title: Int, groups: List<InsertCatalog.Group<T>>, selected: T? = null, pictures: Boolean = false,
        onBack: (() -> Unit)? = null, onPick: (T) -> Unit
    ) {
        lateinit var panel: PanelHost.Panel
        val cell = if (pictures) 96.dp else 64.dp
        val content = composePanel(context) {
            Column(Modifier.padding(bottom = 16.dp)) {
                for (group in groups) {
                    SettingsCaption(group.label)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (item in group.items) {
                            PictureCell(item.value == selected, stringResource(item.label), onClick = {
                                if (onBack == null) panel.dismiss()
                                onPick(item.value)
                                onBack?.invoke()
                            }, modifier = Modifier.size(cell)) {
                                val painter = painterResource(item.icon)
                                // Pictures in their own colors; icons in the text's
                                if (pictures) Image(painter, null, Modifier.fillMaxSize()) else Icon(painter, null, Modifier.fillMaxSize().padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
        panel = panels.show(context.getString(title), content, onBack) {}
    }
}
