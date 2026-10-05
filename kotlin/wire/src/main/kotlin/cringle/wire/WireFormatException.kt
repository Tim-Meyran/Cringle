// SPDX-License-Identifier: Apache-2.0
package cringle.wire

/**
 * Thrown for any violation of the wire format: unknown version, unknown frame type, truncated frame, oversize frame,
 * mode mismatch, or malformed header. See `spec/wire.md` §3, §9, §10.
 */
public class WireFormatException(message: String) : RuntimeException(message)
