// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.common.Identity
import cringle.common.TrustStore

/**
 * The TLS setup of one side of a repository connection (Architecture chapters 5 and 18): the [identity] it presents and
 * the [trustStore] of the peers it accepts, like `RouterTls` of the router. A [RepositoryServer] with it demands a
 * client certificate from a peer in the trust store; a [RepositoryClient] with it accepts only a server in the trust
 * store.
 */
public class RepositoryTls(public val identity: Identity, public val trustStore: TrustStore)
