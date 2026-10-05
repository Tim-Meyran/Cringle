// SPDX-License-Identifier: Apache-2.0

package cringle.common

/**
 * The TLS material of an engine: its own [Identity] and the [TrustStore] of the peers it accepts (the router).
 * With `null` instead of an instance the engine speaks plaintext to the router (dev mode, removed in #86).
 */
public data class EngineTls(public val identity: Identity, public val trustStore: TrustStore)
