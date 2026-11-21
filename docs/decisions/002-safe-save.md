# ADR 002: limited replacement and Save guarantees

Status: adopted for the initial implementation.

rewrite-clj is used to detect forms and positions. The result of `root-string`
for the whole file is never written: the library normalizes line endings. The
original UTF-8 text is preserved, ranges are translated to their original
representation and only the bytes of the chosen form are replaced.

The structural parser does not by itself validate all Clojure reader syntax.
An additional reading pass is added with `*read-eval* false`, user data
readers disabled and a static resolver. The official specs validate the
argument structure of `defn`. Project macros are neither evaluated nor
expanded.

Ambiguous writes, name/kind changes, multi-form replacements, unknown tags,
reader conditionals in `.clj`, invalid Unicode and unsupported formats are
rejected. `.cljc` stays in read-only mode.

The original revision is required and re-checked immediately before the move.
A temporary in the same directory and `ATOMIC_MOVE` are used; there is no
fallback to truncation/direct writing. POSIX permissions are preserved when
the provider allows it and the temporary is always cleaned up.

**Guarantee:** observed external changes are rejected and no partial write is
left visible when the provider supports atomic replacement.
**Limit:** there is no portable compare-and-swap operation by hash. An
external editor can write between the last check and the move. Nor is
durability under crash, full attribute preservation or safety against an
attacker changing directories/links during the operation promised.

This refines the original plan's concurrency criterion. If a stronger
guarantee is needed, a coordination mechanism with other writers or a
different storage backend will have to be agreed.

Sources:

- [rewrite-clj parsing peculiarities](https://cljdoc.org/d/rewrite-clj/rewrite-clj/1.3.59/doc/user-guide)
- [Files.move contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html)
