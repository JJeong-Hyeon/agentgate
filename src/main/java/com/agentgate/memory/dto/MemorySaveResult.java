package com.agentgate.memory.dto;

import java.util.List;

/**
 * What saving a memory did.
 *
 * @param memory    the memory now holding the content: the new one, or the existing one it duplicated
 * @param duplicate the content was already remembered word for word, so nothing new was stored
 * @param replaced  the memory this one replaced (asked for with {@code replaces}); null if none
 * @param similar   other active memories close in meaning, which the content may update or repeat; the caller
 *                  decides, since similarity cannot tell a repeat from a change (e.g. a moved meeting time)
 */
public record MemorySaveResult(AgentMemory memory, boolean duplicate, Long replaced, List<AgentMemory> similar) {
}
