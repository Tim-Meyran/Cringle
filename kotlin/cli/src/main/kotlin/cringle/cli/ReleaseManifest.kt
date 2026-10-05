// SPDX-License-Identifier: Apache-2.0

package cringle.cli

/** The `manifest.json` of a release (docs/releasing.md): the version and one entry per archive. */
internal data class ReleaseManifest(val version: String, val files: List<ReleaseFile>)

/** One archive of a release. */
internal data class ReleaseFile(val name: String, val size: Long, val sha256: String, val minJava: Int)
