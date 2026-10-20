import test from 'node:test'
import assert from 'node:assert/strict'
import { useTextVideoEditor } from './useTextVideoEditor.js'

const cues = [
  { id: 'a', startMs: 0, endMs: 2000, text: '开头' },
  { id: 'b', startMs: 2000, endMs: 4000, text: '删去中段' },
  { id: 'c', startMs: 4000, endMs: 6000, text: '结尾' }
]
const transcript = { id: 11, mediaId: 1, revision: 1, durationMs: 6000, state: 'COMPLETED', cues }
const project = { id: 21, mediaId: 1, transcriptId: 11, version: 1, keptCueIds: ['a', 'b', 'c'] }
function deferred() {
  let resolve, reject
  const promise = new Promise((accept, decline) => { resolve = accept; reject = decline })
  return { promise, resolve, reject }
}
function controlledVideo(play = () => Promise.resolve()) {
  const listeners = new Map()
  return {
    currentTime: 0, paused: true, plays: 0,
    pause() { this.paused = true },
    play() { this.paused = false; return play(++this.plays) },
    load() {},
    addEventListener(name, listener) {
      if (!listeners.has(name)) listeners.set(name, new Set())
      listeners.get(name).add(listener)
    },
    removeEventListener(name, listener) { listeners.get(name)?.delete(listener) },
    emit(name) { for (const listener of [...(listeners.get(name) || [])]) listener() }
  }
}
function setup(overrides = {}) {
  const api = {
    playback: async id => `video-${id}`,
    project: async () => null,
    transcript: async id => ({ ...transcript, mediaId: id }),
    latestExport: async () => null,
    saveProject: async draft => ({ ...draft, id: 21, version: (draft.version || 0) + 1 }),
    ...overrides
  }
  return useTextVideoEditor({ api })
}

test('upload entry generates a missing timeline after loading ends', async context => {
  let editor, submissions = 0
  editor = setup({ transcript: async () => null, transcribe: async id => {
    submissions++
    assert.equal(id, 1)
    assert.equal(editor.state.loading, false)
    return transcript
  } })
  context.after(() => editor.close())
  await editor.open({ id: 1, autoTranscribe: true })
  assert.equal(submissions, 1)
  assert.equal(editor.state.transcript.id, 11)
  assert.deepEqual(editor.state.keptCueIds, ['a', 'b', 'c'])
})

test('ordinary entry and completed or saved timelines do not submit automatic ASR', async context => {
  for (const fixture of [
    { autoTranscribe: false, document: null, saved: null },
    { autoTranscribe: true, document: transcript, saved: null },
    { autoTranscribe: true, document: transcript, saved: { ...project, keptCueIds: ['c'] } },
    { autoTranscribe: true, document: null, saved: project }
  ]) {
    let submissions = 0
    const editor = setup({ project: async () => fixture.saved, transcript: async () => fixture.document,
      transcribe: async () => { submissions++; return transcript } })
    context.after(() => editor.close())
    await editor.open({ id: 1, autoTranscribe: fixture.autoTranscribe })
    assert.equal(submissions, 0)
    if (fixture.saved && fixture.document) assert.deepEqual(editor.state.keptCueIds, ['c'])
    editor.close()
  }
})

test('closing during automatic load prevents new submission or status polling', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  for (const stage of ['transcript', 'playback']) {
    const late = deferred()
    let submissions = 0, polls = 0
    const editor = setup({ transcript: () => stage === 'transcript' ? late.promise : Promise.resolve(null),
      playback: () => stage === 'playback' ? late.promise : Promise.resolve('video'),
      transcribe: async () => { submissions++; return { state: 'QUEUED' } },
      transcriptStatus: async () => { polls++; return transcript } })
    const opening = editor.open({ id: 1, autoTranscribe: true })
    await Promise.resolve()
    await Promise.resolve()
    editor.close()
    late.resolve(null)
    await opening
    context.mock.timers.tick(4000)
    assert.equal(submissions, 0)
    assert.equal(polls, 0)
    assert.equal(editor.state.visible, false)
  }
})

test('upload entry resumes a pending document without installing an invalid empty timeline', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  let submissions = 0, polls = 0
  const pending = { id: 12, mediaId: 1, state: 'RUNNING', durationMs: 0, cues: [] }
  const editor = setup({ transcript: async () => pending,
    transcribe: async () => { submissions++; return pending },
    transcriptStatus: async () => { polls++; return transcript } })
  context.after(() => editor.close())
  await editor.open({ id: 1, autoTranscribe: true })
  assert.equal(submissions, 1)
  assert.equal(editor.state.transcript, null)
  assert.deepEqual(editor.ranges.value, [])
  assert.equal(editor.state.transcribing, true)
  context.mock.timers.tick(2000)
  await Promise.resolve()
  assert.equal(polls, 1)
  assert.equal(editor.state.transcript.id, 11)
  assert.equal(editor.state.transcribing, false)
})

test('ordinary reopening resumes known pending ASR by polling without submitting a model call', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  for (const status of ['QUEUED', 'PROCESSING', 'RUNNING', 'PENDING']) {
    let submissions = 0, polls = 0
    const editor = setup({ transcript: async () => ({ id: 12, mediaId: 1, state: status, durationMs: 0, cues: [] }),
      transcribe: async () => { submissions++; return transcript },
      transcriptStatus: async id => { assert.equal(id, 1); polls++; return transcript } })
    context.after(() => editor.close())
    await editor.open({ id: 1 })
    assert.equal(submissions, 0)
    assert.equal(editor.state.transcript, null)
    assert.deepEqual(editor.ranges.value, [])
    context.mock.timers.tick(2000)
    await Promise.resolve()
    assert.equal(polls, 1, `${status} must resume status polling`)
    assert.equal(editor.state.transcript.id, 11)
    assert.equal(editor.state.transcribing, false)
    context.mock.timers.tick(4000)
    assert.equal(polls, 1)
    editor.close()
  }
})

test('closing or importing subtitles cancels ordinary pending ASR status polling', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  for (const action of ['close', 'import']) {
    let polls = 0, submissions = 0
    const editor = setup({ transcript: async () => ({ id: 12, mediaId: 1, state: 'PROCESSING', durationMs: 0, cues: [] }),
      transcribe: async () => { submissions++; return transcript },
      transcriptStatus: async () => { polls++; return transcript },
      importTranscript: async () => ({ ...transcript, id: 14 }) })
    context.after(() => editor.close())
    await editor.open({ id: 1 })
    assert.equal(editor.state.transcribing, true)
    if (action === 'close') editor.close()
    else await editor.importSubtitles('SRT', 'subtitle')
    context.mock.timers.tick(4000)
    await Promise.resolve()
    assert.equal(polls, 0)
    assert.equal(submissions, 0)
    if (action === 'import') assert.equal(editor.state.transcript.id, 14)
    editor.close()
  }
})

test('closing automatic ASR ignores its late submission and prevents polling', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const late = deferred()
  let polls = 0
  const editor = setup({ transcript: async () => null, transcribe: () => late.promise,
    transcriptStatus: async () => { polls++; return transcript } })
  const opening = editor.open({ id: 1, autoTranscribe: true })
  for (let turn = 0; turn < 8; turn++) await Promise.resolve()
  assert.equal(editor.state.transcribing, true)
  editor.close()
  late.resolve({ state: 'RUNNING', durationMs: 0, cues: [] })
  await opening
  context.mock.timers.tick(4000)
  assert.equal(polls, 0)
  assert.equal(editor.state.visible, false)
})

test('a late automatic ASR status failure cannot overwrite a successful subtitle import', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const late = deferred()
  const editor = setup({ transcript: async () => null,
    transcribe: async () => ({ state: 'RUNNING', durationMs: 0, cues: [] }),
    transcriptStatus: () => late.promise,
    importTranscript: async () => ({ ...transcript, id: 14 }) })
  context.after(() => editor.close())
  await editor.open({ id: 1, autoTranscribe: true })
  context.mock.timers.tick(2000)
  await editor.importSubtitles('SRT', 'subtitle')
  late.reject(new Error('old status request failed'))
  await Promise.resolve()
  assert.equal(editor.state.transcript.id, 14)
  assert.equal(editor.state.error, '')
  assert.equal(editor.state.transcribing, false)
  assert.match(editor.state.notice, /字幕已导入/)
})

test('closing then opening another video discards a late transcript', async () => {
  const late = deferred()
  const editor = setup({ transcript: id => id === 1 ? late.promise : Promise.resolve({ ...transcript, mediaId: id }) })
  const first = editor.open({ id: 1, filename: 'one.mp4' })
  await Promise.resolve()
  editor.close()
  await editor.open({ id: 2, filename: 'two.mp4' })
  late.resolve(transcript)
  await first
  assert.equal(editor.state.mediaId, 2)
  assert.equal(editor.state.transcript.mediaId, 2)
  assert.equal(editor.state.playbackUrl, 'video-2')
  editor.close()
})

test('deleting and restoring a sentence changes ranges without mutating the source timeline', async () => {
  const before = structuredClone(cues)
  const editor = setup()
  await editor.open({ id: 1, filename: 'test.mp4' })
  editor.setKept('b', false)
  assert.deepEqual(editor.ranges.value, [{ startMs: 0, endMs: 2000 }, { startMs: 4000, endMs: 6000 }])
  assert.equal(editor.selectedDurationMs.value, 4000)
  editor.setKept('b', true)
  assert.deepEqual(editor.ranges.value, [{ startMs: 0, endMs: 6000 }])
  assert.deepEqual(cues, before)
  editor.close()
})

test('empty selection is a saveable draft but cannot start preview or export', async () => {
  let exports = 0
  const editor = setup({ createExport: async () => { exports++; return {} } })
  await editor.open({ id: 1, filename: 'test.mp4' })
  editor.deselectAll()
  await editor.save()
  assert.deepEqual(editor.state.project.keptCueIds, [])
  assert.equal(await editor.preview(), false)
  assert.equal(await editor.exportVideo(), false)
  assert.equal(exports, 0)
  editor.close()
})

test('a save conflict preserves the current edit and stale project version', async () => {
  const conflict = Object.assign(new Error('方案已更新，请重新加载'), { status: 409 })
  const editor = setup({ project: async () => project, saveProject: async () => { throw conflict } })
  await editor.open({ id: 1, filename: 'test.mp4' })
  editor.setKept('b', false)
  await editor.save()
  assert.deepEqual([...editor.state.keptCueIds].sort(), ['a', 'c'])
  assert.equal(editor.state.project.version, 1)
  assert.equal(editor.state.dirty, true)
  assert.match(editor.state.error, /重新加载/)
  editor.close()
})

test('reopening a saved project requests its immutable transcript revision', async () => {
  let requestedRevision
  const editor = setup({
    project: async () => project,
    transcript: async (id, transcriptId) => { requestedRevision = transcriptId; return transcript }
  })
  await editor.open({ id: 1, filename: 'test.mp4' })
  assert.equal(requestedRevision, 11)
  assert.deepEqual(editor.state.keptCueIds, project.keptCueIds)
  editor.close()
})

test('preview skips a deleted middle sentence and stops after the final kept segment', async () => {
  const video = { currentTime: 0, paused: true, play: async function () { this.paused = false }, pause() { this.paused = true } }
  const editor = setup()
  await editor.open({ id: 1, filename: 'test.mp4' })
  editor.bindVideo(video)
  editor.setKept('b', false)
  assert.equal(await editor.preview(), true)
  video.currentTime = 2.01
  editor.syncPlayback()
  assert.equal(video.currentTime, 4)
  video.currentTime = 6
  editor.syncPlayback()
  assert.equal(video.paused, true)
  assert.equal(editor.state.previewing, false)
  editor.close()
})

test('a late subtitle import cannot replace another open video', async () => {
  const late = deferred()
  const editor = setup({ importTranscript: () => late.promise })
  await editor.open({ id: 1 })
  const importing = editor.importSubtitles('SRT', 'subtitle')
  await editor.open({ id: 2 })
  late.resolve({ ...transcript, id: 12 })
  await importing
  assert.equal(editor.state.mediaId, 2)
  assert.equal(editor.state.transcript.id, 11)
  editor.close()
})

test('a save response preserves changes made while saving and cannot overwrite an imported timeline', async () => {
  const late = deferred()
  const editor = setup({ saveProject: () => late.promise, importTranscript: async () => ({ ...transcript, id: 12 }) })
  await editor.open({ id: 1 })
  const saving = editor.save()
  editor.setKept('b', false)
  late.resolve({ ...project })
  await saving
  assert.deepEqual(editor.state.keptCueIds, ['a', 'c'])
  assert.equal(editor.state.dirty, true)
  const again = deferred()
  const second = setup({ saveProject: () => again.promise, importTranscript: async () => ({ ...transcript, id: 12 }) })
  await second.open({ id: 1 })
  const stale = second.save()
  await second.importSubtitles('SRT', 'subtitle')
  again.resolve(project)
  await stale
  assert.equal(second.state.transcript.id, 12)
  assert.equal(second.state.project, null)
  assert.equal(second.state.dirty, true)
  editor.close()
  second.close()
})

test('frame URLs are replaced and revoked on close, with stale responses discarded', async () => {
  const late = deferred()
  const revoked = []
  let count = 0
  const editor = useTextVideoEditor({ api: {
    playback: async () => 'video', project: async () => null, transcript: async () => transcript,
    frame: (_, at) => at === 1 ? late.promise : Promise.resolve(new Blob(['image'])),
  }, objectUrls: { createObjectURL: () => `blob:${++count}`, revokeObjectURL: url => revoked.push(url) } })
  await editor.open({ id: 1 })
  await editor.captureFrame(0)
  assert.equal(editor.state.frameUrl, 'blob:1')
  const stale = editor.captureFrame(1)
  await editor.captureFrame(2)
  late.resolve(new Blob(['old']))
  await stale
  assert.equal(editor.state.frameUrl, 'blob:2')
  assert.deepEqual(revoked, ['blob:1'])
  editor.close()
  assert.deepEqual(revoked, ['blob:1', 'blob:2'])
})

test('a late export request cannot attach its job to another media or draft', async () => {
  const late = deferred()
  const editor = setup({ project: async id => id === 1 ? project : null, createExport: () => late.promise })
  await editor.open({ id: 1 })
  const exporting = editor.exportVideo()
  await editor.open({ id: 2 })
  late.resolve({ id: 31, state: 'COMPLETED', projectVersion: 1 })
  await exporting
  assert.equal(editor.state.mediaId, 2)
  assert.equal(editor.state.exportJob, null)
  editor.close()
})

test('close releases playback listeners and pauses the attached video', async () => {
  const listeners = new Map()
  const video = { currentTime: 0, pauseCount: 0, pause() { this.pauseCount++ },
    addEventListener: (name, listener) => listeners.set(name, listener),
    removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name) } }
  const editor = setup()
  await editor.open({ id: 1 })
  editor.bindVideo(video)
  assert.ok(listeners.has('timeupdate'))
  editor.close()
  assert.equal(listeners.size, 0)
  assert.ok(video.pauseCount > 0)
})

test('importing a new timeline allows its save while an old save is pending', async () => {
  const oldSave = deferred(), newSave = deferred()
  let calls = 0
  const editor = setup({ saveProject: () => ++calls === 1 ? oldSave.promise : newSave.promise,
    importTranscript: async () => ({ ...transcript, id: 12 }) })
  await editor.open({ id: 1 })
  const old = editor.save()
  await editor.importSubtitles('SRT', 'subtitle')
  assert.equal(editor.state.saving, false)
  const current = editor.save()
  assert.equal(calls, 2)
  oldSave.resolve(project)
  await old
  assert.equal(editor.state.saving, true)
  newSave.resolve({ ...project, transcriptId: 12 })
  await current
  assert.equal(editor.state.project.transcriptId, 12)
  editor.close()
})

test('refreshing a signed playback URL releases its metadata listener on close', async () => {
  const listeners = new Map()
  const video = { currentTime: 1.5, paused: true, pause() {}, load() {},
    addEventListener: (name, listener) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(listener) },
    removeEventListener: (name, listener) => listeners.get(name)?.delete(listener) }
  const editor = setup()
  await editor.open({ id: 1 })
  editor.bindVideo(video)
  await editor.refreshPlayback()
  editor.close()
  assert.equal([...listeners.values()].reduce((sum, set) => sum + set.size, 0), 0)
})

test('configured ASR failure is actionable and never creates a fabricated timeline', async () => {
  const editor = setup({ transcript: async () => null, transcribe: async () => { throw new Error('ASR_NOT_CONFIGURED') } })
  await editor.open({ id: 1 })
  await editor.transcribe()
  assert.equal(editor.state.transcript, null)
  assert.equal(editor.state.transcribing, false)
  assert.match(editor.state.error, /SRT \/ VTT/)
  assert.match(editor.state.error, /时间戳服务/)
  editor.close()
})

test('an export result is labelled as a previous snapshot after the user changes selection', async () => {
  const late = deferred()
  const editor = setup({ project: async () => project, createExport: () => late.promise })
  await editor.open({ id: 1 })
  const exporting = editor.exportVideo()
  editor.setKept('b', false)
  late.resolve({ id: 31, state: 'COMPLETED', projectVersion: 1 })
  await exporting
  assert.equal(editor.state.exportJob.id, 31)
  assert.equal(editor.exportIsCurrent.value, false)
  editor.close()
})

test('closing cancels export polling and ignores an already pending status response', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const late = deferred()
  let polls = 0
  const editor = setup({ project: async id => id === 1 ? project : null,
    latestExport: async () => ({ id: 31, state: 'QUEUED', projectVersion: 1 }),
    exportStatus: () => { polls++; return late.promise } })
  await editor.open({ id: 1 })
  context.mock.timers.tick(2000)
  assert.equal(polls, 1)
  editor.close()
  await editor.open({ id: 2 })
  late.resolve({ id: 31, state: 'COMPLETED', projectVersion: 1 })
  await Promise.resolve()
  context.mock.timers.tick(4000)
  assert.equal(polls, 1)
  assert.equal(editor.state.exportJob, null)
  editor.close()
})

test('a queued pause event from before preview does not disable the active preview', async () => {
  const listeners = new Map()
  const video = { currentTime: 0, paused: false, pause() { this.paused = true }, play: async function () { this.paused = false },
    addEventListener: (name, listener) => listeners.set(name, listener), removeEventListener: name => listeners.delete(name) }
  const editor = setup()
  await editor.open({ id: 1 })
  editor.bindVideo(video)
  await editor.preview()
  listeners.get('pause')()
  assert.equal(editor.state.previewing, true)
  video.paused = true
  listeners.get('pause')()
  assert.equal(editor.state.previewing, false)
  editor.close()
})

test('an identical saved selection can reuse a completed export from an earlier project version', async () => {
  const editor = setup({
    project: async () => ({ ...project, version: 2, keptCueIds: ['a', 'c'] }),
    latestExport: async () => ({ id: 'export-1', state: 'COMPLETED', projectVersion: 1, mediaId: 1, transcriptId: 11,
      ranges: [{ startMs: 0, endMs: 2000 }, { startMs: 4000, endMs: 6000 }] })
  })
  await editor.open({ id: 1 })
  assert.equal(editor.exportIsCurrent.value, true)
  assert.equal(editor.state.exportJob.projectVersion, 1)
  editor.close()
})

test('a matching version alone cannot make an export from another timeline current', async () => {
  const editor = setup({
    project: async () => project,
    latestExport: async () => ({ id: 'export-2', state: 'COMPLETED', projectVersion: 1, mediaId: 1, transcriptId: 99,
      ranges: [{ startMs: 0, endMs: 6000 }] })
  })
  await editor.open({ id: 1 })
  assert.equal(editor.exportIsCurrent.value, false)
  editor.close()
})

test('an interrupted old preview rejection cannot pause a newer successful preview', async context => {
  const firstPlay = deferred()
  const video = controlledVideo(call => call === 1 ? firstPlay.promise : Promise.resolve())
  const editor = setup()
  context.after(() => editor.close())
  await editor.open({ id: 1 })
  editor.bindVideo(video)
  const oldPreview = editor.preview()
  editor.stopPreview()
  assert.equal(await editor.preview(), true)
  firstPlay.reject(new Error('The play() request was interrupted by a call to pause()'))
  assert.equal(await oldPreview, false)
  assert.equal(editor.state.previewing, true)
  assert.equal(video.paused, false)
  assert.equal(editor.state.error, '')
})

test('manual seek or stop supersedes a refresh before its URL or metadata arrives', async context => {
  for (const stage of ['url', 'metadata']) {
    for (const action of ['seek', 'stop']) {
      const refreshUrl = deferred()
      let calls = 0
      const video = controlledVideo()
      const editor = setup({ playback: () => ++calls === 1 ? Promise.resolve('video') : refreshUrl.promise })
      context.after(() => editor.close())
      await editor.open({ id: 1 })
      editor.bindVideo(video)
      await editor.preview()
      video.currentTime = 1
      const refresh = editor.refreshPlayback()
      if (stage === 'metadata') { refreshUrl.resolve('refreshed-video'); await refresh }
      if (action === 'seek') editor.seek(cues[2])
      else editor.stopPreview()
      const expectedPosition = action === 'seek' ? 4 : 1
      if (stage === 'url') { refreshUrl.resolve('refreshed-video'); await refresh }
      video.emit('loadedmetadata')
      await Promise.resolve()
      assert.equal(video.currentTime, expectedPosition, `${action} while waiting for ${stage} must retain position`)
      assert.equal(video.paused, true, `${action} while waiting for ${stage} must not autoplay`)
      assert.equal(editor.state.previewing, false)
      editor.close()
    }
  }
})

test('an interrupted refresh-resume rejection cannot stop a newer preview', async context => {
  const resumedPlay = deferred()
  const video = controlledVideo(call => call === 2 ? resumedPlay.promise : Promise.resolve())
  const editor = setup()
  context.after(() => editor.close())
  await editor.open({ id: 1 })
  editor.bindVideo(video)
  await editor.preview()
  await editor.refreshPlayback()
  video.emit('loadedmetadata')
  assert.equal(video.plays, 2)
  editor.stopPreview()
  assert.equal(await editor.preview(), true)
  resumedPlay.reject(new Error('Old resume was interrupted'))
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(editor.state.previewing, true)
  assert.equal(video.paused, false)
  assert.equal(editor.state.error, '')
})

test('a late refresh-resume completion cannot replace or leak a newer boundary timer', async context => {
  const intervals = new Set()
  let intervalId = 0
  context.mock.method(globalThis, 'setInterval', () => { const id = ++intervalId; intervals.add(id); return id })
  context.mock.method(globalThis, 'clearInterval', id => intervals.delete(id))
  const resumedPlay = deferred()
  const video = controlledVideo(call => call === 2 ? resumedPlay.promise : Promise.resolve())
  const editor = setup()
  context.after(() => editor.close())
  await editor.open({ id: 1 })
  editor.bindVideo(video)
  await editor.preview()
  await editor.refreshPlayback()
  video.emit('loadedmetadata')
  editor.stopPreview()
  await editor.preview()
  const newerTimers = [...intervals]
  assert.equal(newerTimers.length, 1)
  resumedPlay.resolve()
  await Promise.resolve()
  await Promise.resolve()
  assert.deepEqual([...intervals], newerTimers)
  editor.close()
  assert.equal(intervals.size, 0)
})
