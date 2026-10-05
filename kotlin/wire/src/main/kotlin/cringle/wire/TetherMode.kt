// SPDX-License-Identifier: Apache-2.0
package cringle.wire

/**
 * The mode of a tether on the wire. One mode per tether; the codec rejects a frame the tether was not declared to
 * carry. See `spec/wire.md` §2.
 */
public enum class TetherMode {
    /** The tether carries typed frames (MESSAGE, REQUEST, RESPONSE, STREAM_OPEN, STREAM_ITEM, STREAM_CLOSE, ERROR). */
    TYPED,

    /** The tether carries raw bytes (BYTES frame only). */
    BYTES,
}
