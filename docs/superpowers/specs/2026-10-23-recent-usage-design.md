# Recent usage in session and host lists

Based on PR #218 at `7250072`. The user requested recently used entries first and confirmed keeping the existing session groups, with recency ordering inside each group.

- Opening a terminal or its file browser records use of that session and host. Opening host details or a desktop records use of the host. Terminal switching and notification desktop entry count too.
- Preserve WAITING / WORKING / DONE / OTHER group priority and terminal tab order. Within a group, recent use wins over connection status. Unused entries keep their previous relative order; unused hosts remain alphabetical.
- Store only ordered session and host IDs locally, so ordering survives activity/process recreation. Remove IDs when their entries disappear, after session restoration completes.
- Background reconnects, reachability probes, agent reports and metadata edits do not count as use.

## Approach

Use a small SharedPreferences-backed recent-ID order and apply it to displayed lists. This keeps usage independent of transport replacement and host metadata updates, and avoids changing the Room schema or session journal. Adding timestamps to both existing storage formats would require a migration and coupling to transport restoration for the same behavior; an in-memory order would lose the user's ordering on restart.

Implementation choice: record navigation to a visible destination centrally in `ItermuxScreen`; use stable sorting for entries without recorded use. No new controls or dependencies.
