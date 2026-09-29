// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef

/**
 * Thrown when a document is added whose namespace already exists in the registry.
 *
 * @property namespace the namespace that is defined twice.
 * @property existingOrigin the origin of the document already in the registry.
 * @property newOrigin the origin of the document that was rejected.
 */
public class SchemaConflictException(
    public val namespace: String,
    public val existingOrigin: String,
    public val newOrigin: String,
) : RuntimeException("Namespace '$namespace' is defined by both '$existingOrigin' and '$newOrigin'")

/**
 * A problem in the registry as a whole.
 *
 * @property origin the origin of the document that contains the problem.
 * @property path the JSON path of the problem within that document.
 * @property message what is wrong.
 */
public data class SchemaProblem(public val origin: String, public val path: String, public val message: String)

/**
 * Holds schema documents from any number of sources and resolves types by namespace ID across their
 * boundaries. It is a pure library and needs no engine, so Repository and ManagementServer can use it too.
 *
 * A registry always contains the standard types of `cringle.std`. A namespace exists once per registry;
 * documents of the same namespace are never merged.
 */
public class SchemaRegistry {
    private val documents = LinkedHashMap<String, SchemaDocument>()

    init {
        documents[StandardSchemas.document.namespace] = StandardSchemas.document
    }

    /**
     * Adds [document].
     *
     * @throws SchemaConflictException if the registry already has a document with the same namespace.
     */
    public fun add(document: SchemaDocument) {
        documents[document.namespace]?.let { throw SchemaConflictException(document.namespace, it.origin, document.origin) }
        documents[document.namespace] = document
    }

    /** Returns the definition of [ref], or `null` if its namespace or name is unknown. */
    public fun resolve(ref: SchemaRef): TypeDef? = documents[ref.namespace]?.types?.get(ref.name)

    /**
     * Checks the registry as a whole and returns everything that is wrong: references that cannot be resolved,
     * and cycles through required record fields (a value of such a type could never be constructed). Cycles
     * through `list`, `map` or `optional` are allowed and produce no problem.
     */
    public fun problems(): List<SchemaProblem> = unresolvedReferences() + requiredCycles()

    private fun unresolvedReferences(): List<SchemaProblem> {
        val problems = ArrayList<SchemaProblem>()
        for (document in documents.values) {
            for ((typeName, definition) in document.types) {
                if (definition !is TypeDef.Record) continue
                for ((field, expression) in definition.fields) {
                    collectUnresolved(document, expression, "$.types.$typeName.record.$field", problems)
                }
            }
        }
        return problems
    }

    private fun collectUnresolved(document: SchemaDocument, expression: TypeExpr, path: String, into: MutableList<SchemaProblem>) {
        when (expression) {
            is TypeExpr.Ref ->
                if (resolve(expression.ref) == null) {
                    into += SchemaProblem(document.origin, path, "unresolved reference '${expression.ref}'")
                }
            is TypeExpr.ListOf -> collectUnresolved(document, expression.element, "$path.list", into)
            is TypeExpr.MapOf -> collectUnresolved(document, expression.value, "$path.map", into)
            is TypeExpr.Optional -> collectUnresolved(document, expression.inner, "$path.optional", into)
        }
    }

    private fun requiredCycles(): List<SchemaProblem> {
        val problems = ArrayList<SchemaProblem>()
        val finished = HashSet<SchemaRef>()
        val reported = HashSet<Set<SchemaRef>>()

        fun visit(node: SchemaRef, stack: MutableList<SchemaRef>) {
            if (node in finished) return
            val position = stack.indexOf(node)
            if (position >= 0) {
                val cycle = stack.subList(position, stack.size).toList()
                if (reported.add(cycle.toSet())) {
                    val first = cycle.first()
                    val origin = documents.getValue(first.namespace).origin
                    val route = (cycle + first).joinToString(" -> ")
                    problems += SchemaProblem(origin, "$.types.${first.name}", "cycle through required fields: $route")
                }
                return
            }
            val record = resolve(node) as? TypeDef.Record ?: return
            stack += node
            for (expression in record.fields.values) {
                if (expression is TypeExpr.Ref) visit(expression.ref, stack)
            }
            stack.removeAt(stack.lastIndex)
            finished += node
        }

        for (document in documents.values) {
            for (typeName in document.types.keys) visit(SchemaRef(document.namespace, typeName), ArrayList())
        }
        return problems
    }
}

/**
 * Whether values of type [from] can be used where [to] is expected. The check is nominal: both must resolve in
 * [registry] and be the same namespace ID. Structural rules are deliberately left out and can be added later
 * without changing this signature.
 */
public fun isAssignable(from: SchemaRef, to: SchemaRef, registry: SchemaRegistry): Boolean =
    from == to && registry.resolve(from) != null
