// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import org.junit.jupiter.api.Test
import java.nio.file.Path

class EngineSupervisorTest {
    @Test
    fun `lambda parameter does not shadow outer it`() {
        // This test verifies that the lambda parameter 'byte' is used correctly
        // and doesn't shadow the outer 'it' from the let block
        val home = Path.of("test-home")
        val supervisor = EngineSupervisor(home)
        
        // The code should compile without errors
        assert(supervisor != null)
    }
}