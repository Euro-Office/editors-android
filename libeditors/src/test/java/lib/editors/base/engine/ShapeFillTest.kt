package lib.editors.base.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShapeFillTest {

    @Test
    fun `parses the engine's fills`() {
        assertNull(ShapeFill.parse("null"))
        assertEquals(ShapeFill.None, ShapeFill.parse("""{"type": "none"}"""))
        assertEquals(ShapeFill.Solid("FF0000"), ShapeFill.parse("""{"type": "solid", "color": "FF0000"}"""))
        assertEquals(
            ShapeFill.Gradient(listOf(GradientStop("FF0000", 0), GradientStop("0000FF", 100)), angle = 90),
            ShapeFill.parse("""{"type": "gradient", "radial": false, "angle": 90.00001, "stops": [{"color": "FF0000", "position": 0}, {"color": "0000FF", "position": 100}]}""")
        )
        assertEquals(ShapeFill.Pattern(6, "0000FF", "FFFF00"), ShapeFill.parse("""{"type": "pattern", "pattern": 6, "foreground": "0000FF", "background": "FFFF00"}"""))
        assertEquals(ShapeFill.Texture(3), ShapeFill.parse("""{"type": "texture", "texture": 3}"""))
        assertEquals(ShapeFill.Picture(tile = true), ShapeFill.parse("""{"type": "picture", "tile": true}"""))
    }

    @Test
    fun `writes the engine's JSON`() {
        val gradient = ShapeFill.Gradient(listOf(GradientStop("theme:24", 0), GradientStop("FFFFFF", 100)), radial = true).toJson()
        assertEquals("gradient", gradient.getString("type"))
        assertEquals(true, gradient.getBoolean("radial"))
        assertEquals("theme:24", gradient.getJSONArray("stops").getJSONObject(0).getString("color"))
        val picture = ShapeFill.Picture("image1.png", 640, 480, tile = false).toJson()
        assertEquals("image1.png", picture.getString("picture"))
        assertEquals(480, picture.getInt("height"))
    }

    @Test
    fun `parses fill resources`() {
        val resources = FillResources.parse("""{"patterns": ["${"0".repeat(64)}"], "textures": ["data:image/png;base64,AA=="]}""")
        assertEquals(1, resources.patterns.size)
        assertEquals("data:image/png;base64,AA==", resources.textures[0])
    }
}
