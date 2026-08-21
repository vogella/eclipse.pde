# NPE in DependencyManager.findRequirementsClosure on a stale bundle wiring

## Symptom

Reported from a workspace with 755 projects, after a p2 update was applied to the running IDE and roughly a hundred plug-in projects were closed and reopened in bulk.

```
java.lang.NullPointerException
    at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:288)
    at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
    at java.base/java.util.Spliterators$IteratorSpliterator.tryAdvance(Spliterators.java:1950)
    ...
    at java.base/java.util.Spliterators$1Adapter.hasNext(Spliterators.java:669)
    at org.eclipse.pde.internal.core.DependencyManager.findRequirementsClosure(DependencyManager.java:267)
    at org.eclipse.pde.internal.core.ClasspathComputer.collectBuildRelevantDependencies(ClasspathComputer.java:180)
    at org.eclipse.pde.internal.core.DynamicPluginProjectReferences.getDependentProjects(DynamicPluginProjectReferences.java:47)
    at org.eclipse.core.internal.resources.ProjectDescription.computeDynamicReferencesForProject(ProjectDescription.java:1037)
    ...
    at org.eclipse.ui.internal.ide.misc.ProjectReferenceGraph.rebuild(ProjectReferenceGraph.java:147)
    at org.eclipse.core.internal.jobs.Worker.run(Worker.java:63)
```

## Root cause

This is a time-of-check/time-of-use race, not a plain missing null check.

`DependencyManager.findRequirementsClosure` guards each visited bundle at lines 229-232:

```java
BundleWiring wiring = bundle.getWiring();
if (wiring == null || !wiring.isInUse()) {
    continue;
}
```

The guard passes, the wiring is captured in a local, and the wiring is then invalidated underneath it before the wires are actually read at line 266.

The wiring here comes from PDE's own `State` (the Equinox compatibility resolver), not from the live framework.
`BundleDescriptionImpl.DescriptionWiring.getRequiredWires(String)` returns `null` verbatim when `!isInUse()`, where `isInUse()` is `valid && (isCurrent() || hasDependents())` and `valid` is a volatile flipped by `invalidate()`.

The invalidation path is short:

```
StateImpl.resolve(false)          // or StateImpl.removeBundle(...)
  -> flush(getBundles())
  -> resolveBundle(bundle, false, ...)
  -> BundleDescriptionImpl.setStateBit(RESOLVED, false)
  -> bundleWiring.invalidate()
```

`PluginModelManager.handleRemove` and `handleChange` call `fState.removeBundleDescription(...)` on every project close, project open, and manifest change, and a target platform reload re-resolves the whole state.
Any of those invalidates live wiring objects.

There is no mutual exclusion between the two sides.
`StateImpl` mutates under its own monitor, `DescriptionWiring.getRequiredWires` only reads the volatile flag, and `findRequirementsClosure` holds no lock at all.
`ProjectReferenceGraph` runs its rebuild in a background job, so it races freely with the model updates that a bulk close/open produces.

The lazy stream widens the window.
`getRequiredWires` is not called at line 265 where the `Iterable` is built, it is called during the `for` loop at line 267, so the gap after the `isInUse()` check spans the whole fragment block in between.

## Affected sites

| Location | Exposure |
| --- | --- |
| `DependencyManager:266` | The reported crash. `map(wiring::getRequiredWires).flatMap(List::stream)` over a `null`. |
| `DependencyManager:254` | `getRequiredWires(HOST_NAMESPACE)` in a for-each, fragment branch. |
| `DependencyManager:265` | The `namespaces.isEmpty()` branch, `getRequiredWires(null)` in a for-each. |
| `ClasspathUtilCore:130` | `wires.stream()` on the same `State` wiring, unguarded. |
| `ClasspathUtilCore:171` | Live framework wiring. Also calls `wire.getProviderWiring().getBundle()` without the null filter its sibling at line 131 has. |

No matching issue exists in the eclipse-pde tracker (searched open and closed).

## Reproduction

`DependencyManagerTest.testFindRequirementsClosure_stateReResolvedDuringTraversal`, added in this branch, reproduces it deterministically and currently fails.

A real second thread would be flaky in CI, so the timing comes from the `namespaces` set that the caller passes in.
`findRequirementsClosure` builds a fresh `namespaces.stream()` per visited bundle, and the stream is lazy, so `iterator.next()` yields a namespace and only then is `wiring.getRequiredWires(namespace)` applied.
The test's set counts `iterator()` calls to identify the second visited bundle and calls `state.resolve(false)` from inside `next()`, which lands exactly in the window.

The mutation itself is real PDE behavior rather than a mock.
Only the scheduling is synthetic.

The produced stack matches the reported one frame for frame, including `ReferencePipeline.java:288` and `DependencyManager.java:267`.
The single differing line is `AbstractWrappingSpliterator.doAdvance`, which is a JDK build difference and not a different code path.

## Proposed fix

Treat a `null` return from `getRequiredWires` exactly like the `!wiring.isInUse()` case that the code already skips on, at all five sites, and add the missing `getProviderWiring()` null filter in `ClasspathUtilCore:171`.

This is deliberately not a symptom fix.
A `null` return means precisely the condition line 230 already skips on, only discovered a few microseconds later, so tolerating it makes an existing policy race-safe rather than introducing a new one.

The obvious objection is that a silently incomplete closure means missing project references and therefore wrong build order.
That is true, but it does not favour any other option:

- A closure computed while the state is being re-resolved is stale no matter how it terminates.
  The model change fires a delta and the references get recomputed.
- Crashing is strictly worse, because `ProjectReferenceGraph` is then left unbuilt rather than merely recomputed.
- Refreshing or re-resolving from inside the traversal is the wrong lever.
  `findRequirementsClosure` is a static utility with no ownership of the state and no retry API, and re-resolving from inside a walk invites worse races.

## Open questions for upstream

Whether the test should pin the degraded result (`{c, b}`, `bundle.a` lost) as it currently does, or assert only that the walk completes.
Pinning documents the truncation honestly but encodes behavior that a reviewer may prefer to change rather than enshrine.

Whether `DependencyManager` should signal a truncated closure to its callers at all, for instance so `DynamicPluginProjectReferences` could decline to report references it knows are incomplete.
That is a larger design question and is out of scope for the crash fix.
