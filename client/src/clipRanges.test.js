import test from 'node:test'
import assert from 'node:assert/strict'
import { buildClipRanges } from './clipRanges.js'

const cue = (id, startMs, endMs) => ({ id, startMs, endMs })
test('deleting middle preserves time order and deduplicates selection', () => {
  assert.deepEqual(buildClipRanges([cue('a', 0, 2000), cue('b', 2000, 4000), cue('c', 4000, 6000)], ['c', 'a', 'a'], 6000), [
    { startMs: 0, endMs: 2000 }, { startMs: 4000, endMs: 6000 }
  ])
})
test('sorts timeline and merges overlapping and adjacent ranges', () => {
  assert.deepEqual(buildClipRanges([cue('c', 3000, 5000), cue('a', 0, 2000), cue('b', 1000, 3000)], ['c', 'a', 'b'], 6000), [{ startMs: 0, endMs: 5000 }])
})
test('empty selection is a valid draft', () => assert.deepEqual(buildClipRanges([cue('a', 0, 1000)], [], 1000), []))
test('rejects unknown selections and duplicate or blank cue IDs', () => {
  assert.throws(() => buildClipRanges([cue('a', 0, 1)], ['old'], 1), /unknown/i)
  assert.throws(() => buildClipRanges([cue('a', 0, 1), cue('a', 1, 2)], [], 2), /duplicate/i)
  assert.throws(() => buildClipRanges([cue(' ', 0, 1)], [], 2), /id/i)
})
test('rejects invalid times even on unselected cues', () => {
  for (const [start, end] of [[-1, 1], [1, 1], [2, 1], [0, 3], [0.5, 1], [0, Infinity], [0, Number.MAX_SAFE_INTEGER + 1]]) {
    assert.throws(() => buildClipRanges([cue('a', start, end)], [], 2), /time|range|integer/i)
  }
})
test('validates inputs and requires positive safe integer duration', () => {
  for (const duration of [0, -1, 1.5, Infinity, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => buildClipRanges([], [], duration), /duration/i)
  assert.throws(() => buildClipRanges(null, [], 1))
  assert.throws(() => buildClipRanges([], null, 1))
})
