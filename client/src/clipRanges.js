export function buildClipRanges(cues, keptCueIds, durationMs) {
  if (!Number.isSafeInteger(durationMs) || durationMs <= 0) throw new Error('Invalid duration')
  if (!Array.isArray(cues) || !Array.isArray(keptCueIds)) throw new Error('Cues and selected IDs must be arrays')
  const byId = new Map()
  for (const cue of cues) {
    if (!cue || typeof cue.id !== 'string' || !cue.id.trim()) throw new Error('Invalid cue ID')
    if (byId.has(cue.id)) throw new Error(`Duplicate cue ID: ${cue.id}`)
    if (!Number.isSafeInteger(cue.startMs) || !Number.isSafeInteger(cue.endMs) || cue.startMs < 0 || cue.startMs >= cue.endMs || cue.endMs > durationMs) throw new Error(`Invalid time range for cue ${cue.id}`)
    byId.set(cue.id, cue)
  }
  const selected = [...new Set(keptCueIds)].map(id => {
    if (!byId.has(id)) throw new Error(`Unknown cue ID: ${id}`)
    return byId.get(id)
  }).sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs)
  const ranges = []
  for (const cue of selected) {
    const last = ranges.at(-1)
    if (last && cue.startMs <= last.endMs) last.endMs = Math.max(last.endMs, cue.endMs)
    else ranges.push({ startMs: cue.startMs, endMs: cue.endMs })
  }
  return ranges
}
