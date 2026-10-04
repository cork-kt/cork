package cork

import kotlin.test.Test
import kotlin.test.assertEquals

class CorkApiTest {
    @Test
    fun formatIdsAreStable() {
        assertEquals(1, ContainerFormat.Zip.id)
        assertEquals(2, ContainerFormat.SevenZ.id)
        assertEquals(3, ContainerFormat.Lzma.id)
    }
}
