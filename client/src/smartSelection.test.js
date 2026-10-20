import test from 'node:test'
import assert from 'node:assert/strict'
import { useTextVideoEditor } from './useTextVideoEditor.js'

const transcript = { id: 11, mediaId: 1, revision: 1, durationMs: 6000, state: 'COMPLETED', cues: [
  { id: 'a', startMs: 0, endMs: 2000, text: '问题' },
  { id: 'b', startMs: 2000, endMs: 4000, text: '无关' },
  { id: 'c', startMs: 4000, endMs: 6000, text: '回答' }
] }
const job = { id: 'job-1', mediaId: 1, transcriptId: 11, goal: '保留问答', state: 'COMPLETED', keptCueIds: ['a', 'c'], selections: [{ cueIds: ['a', 'c'], reason: '完整问答' }], durationMs: 4000, errorCode: null, errorMessage: null, completedBatches: 1, totalBatches: 1 }
const deferred = () => { let resolve; const promise = new Promise(accept => { resolve = accept }); return { promise, resolve } }
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
function setup(overrides = {}) {
  return useTextVideoEditor({ api: {
    playback: async () => 'video', project: async () => null,
    transcript: async mediaId => ({ ...transcript, mediaId }),
    latestSelection: async () => null, createSelection: async () => job, selectionStatus: async () => job,
    ...overrides
  } })
}

test('recommendation leaves draft untouched until explicit application and never saves or exports', async t => {
  const editor = setup({ createSelection: async body => { assert.deepEqual(body, { mediaId: 1, transcriptId: 11, goal: '保留问答' }); return job },
    saveProject: () => assert.fail('must not save'), createExport: () => assert.fail('must not export') })
  t.after(editor.close)
  await editor.open({ id: 1 })
  editor.state.selectionGoal = ' 保留问答 '
  assert.equal(await editor.generateSelection(), true)
  assert.deepEqual(editor.state.keptCueIds, ['a', 'b', 'c'])
  assert.equal(editor.selectionCanApply.value, true)
  assert.equal(editor.applySelection(), true)
  assert.deepEqual(editor.state.keptCueIds, ['a', 'c'])
  assert.equal(editor.state.dirty, true)
})

test('manual edits during generation are protected even when toggled back', async t => {
  const late = deferred(), editor = setup({ createSelection: () => late.promise })
  t.after(editor.close)
  await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal
  const pending = editor.generateSelection()
  editor.setKept('b', false); editor.setKept('b', true)
  const manualDraft = [...editor.state.keptCueIds]
  late.resolve(job); await pending
  assert.equal(editor.selectionCanApply.value, false)
  assert.equal(editor.applySelection(), false)
  assert.deepEqual(editor.state.keptCueIds, manualDraft)
  assert.match(editor.state.selectionError, /改动|变化/)
})

test('empty recommendation cannot clear the draft and invalid response IDs are rejected', async t => {
  for (const result of [{ ...job, keptCueIds: [], selections: [], durationMs: 0 }, { ...job, keptCueIds: ['foreign'] }, { ...job, selections: [{ cueIds: ['foreign'], reason: 'bad' }] }, { ...job, transcriptId: 12 }]) {
    const editor = setup({ createSelection: async () => result }); t.after(editor.close)
    await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal
    await editor.generateSelection()
    assert.equal(editor.selectionCanApply.value, false)
    assert.equal(editor.applySelection(), false)
    assert.deepEqual(editor.state.keptCueIds, ['a', 'b', 'c'])
    editor.close()
  }
})

test('invalid goals never submit, failed job can be regenerated', async t => {
  let calls = 0
  const editor = setup({ createSelection: async () => { calls++; return calls === 1 ? { ...job, state: 'FAILED', errorCode: 'TIMEOUT', errorMessage: '模型超时' } : job } }); t.after(editor.close)
  await editor.open({ id: 1 })
  for (const goal of [' ', 'a'.repeat(501)]) { editor.state.selectionGoal = goal; assert.equal(await editor.generateSelection(), false) }
  assert.equal(calls, 0)
  editor.state.selectionGoal = job.goal
  await editor.generateSelection()
  assert.equal(editor.state.selectionJob.state, 'FAILED')
  assert.match(editor.state.selectionError, /超时/)
  assert.equal(await editor.generateSelection(), true)
  assert.equal(editor.selectionCanApply.value, true)
})

test('close and authentication changes discard late recommendation callbacks', async t => {
  for (const mode of ['close', 'media', 'auth', 'timeline']) {
    const late = deferred(); let valid = true
    const editor = setup({ captureSession: () => () => valid, createSelection: () => late.promise,
      importTranscript: async () => ({ ...transcript, id: 12 }) }); t.after(editor.close)
    await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal
    const pending = editor.generateSelection()
    if (mode === 'close') editor.close()
    if (mode === 'media') await editor.open({ id: 2 })
    if (mode === 'auth') valid = false
    if (mode === 'timeline') await editor.importSubtitles('srt', 'content')
    late.resolve(job); await pending
    assert.equal(editor.state.selectionJob, null)
    assert.equal(editor.applySelection(), false)
    editor.close()
  }
})

test('reopen restores queued job, polls to completion, requires explicit restored replacement confirmation', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let polls = 0
  const editor = setup({ latestSelection: async () => ({ ...job, state: 'PROCESSING', completedBatches: 0 }), selectionStatus: async id => { assert.equal(id, 'job-1'); polls++; return job } }); t.after(editor.close)
  await editor.open({ id: 1 })
  assert.equal(editor.state.selectionRestored, true)
  assert.equal(editor.state.selectionGoal, job.goal)
  t.mock.timers.tick(2000); await flush()
  assert.equal(polls, 1)
  assert.equal(editor.state.selectionJob.state, 'COMPLETED')
  assert.equal(editor.applySelection(), false)
  assert.equal(editor.applySelection({ confirmRestored: true }), true)
  assert.deepEqual(editor.state.keptCueIds, ['a', 'c'])
  editor.close(); t.mock.timers.tick(4000); await flush()
  assert.equal(polls, 1)
})

test('late recovery cannot replace a newer explicit generation', async t => {
  const late = deferred(), editor = setup({ latestSelection: () => late.promise }); t.after(editor.close)
  const opening = editor.open({ id: 1 }); await flush()
  editor.state.selectionGoal = '新目标'
  await editor.generateSelection()
  late.resolve({ ...job, id: 'older', goal: '旧目标' }); await opening
  assert.equal(editor.state.selectionJob.id, 'job-1')
  assert.equal(editor.state.selectionRestored, false)
})

test('poll errors are visible and recoverable; late polls cannot replace another media', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let fail = true
  const late = deferred()
  const editor = setup({ createSelection: async () => ({ ...job, state: 'QUEUED' }), selectionStatus: () => fail ? Promise.reject(new Error('状态连接失败')) : late.promise }); t.after(editor.close)
  await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal; await editor.generateSelection()
  t.mock.timers.tick(2000); await flush()
  assert.match(editor.state.selectionError, /连接失败/)
  fail = false; const refresh = editor.restoreSelection()
  await flush(); await refresh
  await editor.generateSelection(); t.mock.timers.tick(2000); await flush()
  await editor.open({ id: 2 }); late.resolve(job); await flush()
  assert.equal(editor.state.selectionJob, null)
})


test('expired authentication blocks applying an already displayed recommendation', async t => {
  let valid = true
  const editor = setup({ captureSession: () => () => valid }); t.after(editor.close)
  await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal; await editor.generateSelection()
  assert.equal(editor.selectionCanApply.value, true)
  valid = false
  assert.equal(editor.applySelection(), false)
  assert.deepEqual(editor.state.keptCueIds, ['a', 'b', 'c'])
})

test('restored recommendation cannot overwrite manual edits made after recovery started', async t => {
  const late = deferred(), editor = setup({ latestSelection: () => late.promise }); t.after(editor.close)
  await editor.open({ id: 1 })
  editor.setKept('a', false)
  late.resolve(job); await flush()
  assert.equal(editor.applySelection({ confirmRestored: true }), false)
  assert.deepEqual(editor.state.keptCueIds, ['b', 'c'])
})

test('closing then reopening recovers the same background task without another model submission', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let latest = null, creates = 0, polls = 0
  const editor = setup({ latestSelection: async () => latest,
    createSelection: async () => { creates++; latest = { ...job, state: 'PROCESSING' }; return latest },
    selectionStatus: async () => { polls++; return job } }); t.after(editor.close)
  await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal; await editor.generateSelection()
  editor.close(); t.mock.timers.tick(4000); await flush()
  assert.equal(polls, 0)
  await editor.open({ id: 1 })
  assert.equal(editor.state.selectionJob.id, 'job-1')
  t.mock.timers.tick(2000); await flush()
  assert.equal(editor.state.selectionJob.state, 'COMPLETED')
  assert.equal(creates, 1)
  assert.equal(polls, 1)
})

test('a save conflict after application preserves the recommended draft and saved version', async t => {
  const editor = setup({ project: async () => ({ id: 21, mediaId: 1, transcriptId: 11, version: 2, keptCueIds: ['a', 'b', 'c'] }),
    saveProject: async draft => { assert.deepEqual(draft.keptCueIds, ['a', 'c']); throw Object.assign(new Error('conflict'), { status: 409 }) } }); t.after(editor.close)
  await editor.open({ id: 1 }); editor.state.selectionGoal = job.goal; await editor.generateSelection()
  assert.equal(editor.applySelection(), true)
  assert.equal(await editor.save(), false)
  assert.deepEqual(editor.state.keptCueIds, ['a', 'c'])
  assert.equal(editor.state.project.version, 2)
  assert.equal(editor.state.dirty, true)
  assert.match(editor.state.error, /重新加载/)
})
