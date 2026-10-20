import { reactive, computed } from 'vue'
import { buildClipRanges } from './clipRanges.js'
import { editorApi } from './editorApi.js'

const terminal = job => !job || ['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.state)
const message = error => error?.message || '操作失败，请稍后重试'

export function useTextVideoEditor({ api = editorApi, objectUrls = globalThis.URL } = {}) {
  const initial = () => ({
    visible: false, mediaId: null, filename: '', loading: false, error: '', notice: '',
    transcript: null, project: null, keptCueIds: [], dirty: false, saving: false, importing: false,
    playbackUrl: '', playbackError: '', currentTimeMs: 0, currentCueId: null, previewing: false,
    frameUrl: '', frameAtMs: null, frameLoading: false, frameError: '',
    exportJob: null, exporting: false, exportError: '', downloadUrl: '', downloading: false, transcribing: false,
    selectionGoal: '', selectionJob: null, selectionError: '', selectionSubmitting: false,
    selectionRestoring: false, selectionRestored: false, selectionApplied: false
  })
  const state = reactive(initial())
  let generation = 0
  let timelineRevision = 0
  let editRevision = 0
  let importRequest = 0
  let frameRequest = 0
  let saveRequest = 0
  let previewRequest = 0
  let video = null
  let boundaryTimer = null
  let exportTimer = null
  let asrTimer = null
  let selectionTimer = null
  let selectionRequest = 0
  let selectionSnapshot = null
  let currentSession = () => true
  let refreshingPlayback = false
  let playbackRefreshAttempts = 0
  let restoreMetadata = null
  const active = token => token === generation && state.visible && currentSession()
  const playbackActive = (token, requestId, element) => active(token) && requestId === previewRequest && video === element
  const ranges = computed(() => state.transcript ? buildClipRanges(state.transcript.cues, state.keptCueIds, state.transcript.durationMs) : [])
  const selectedDurationMs = computed(() => ranges.value.reduce((sum, range) => sum + range.endMs - range.startMs, 0))
  const selectionFingerprint = () => JSON.stringify([...state.keptCueIds].sort())
  const selectionConflict = computed(() => !!state.selectionJob && (!selectionSnapshot ||
    selectionSnapshot.timeline !== timelineRevision || selectionSnapshot.revision !== editRevision ||
    selectionSnapshot.fingerprint !== selectionFingerprint()))
  const selectionBusy = computed(() => state.selectionSubmitting || state.selectionRestoring ||
    !!state.selectionJob && !terminal(state.selectionJob) && !state.selectionError)
  const selectionCanApply = computed(() => state.visible && currentSession() &&
    state.selectionJob?.state === 'COMPLETED' && state.selectionJob.keptCueIds?.length > 0 &&
    !selectionConflict.value && !state.selectionApplied && !state.importing && !state.selectionSubmitting && !state.selectionRestoring)
  const exportIsCurrent = computed(() => {
    const job = state.exportJob, document = state.transcript
    if (!job || !state.project || state.dirty || !document || !Array.isArray(job.ranges)) return false
    if (String(job.mediaId) !== String(state.mediaId) || String(job.transcriptId) !== String(document.id)) return false
    return job.ranges.length === ranges.value.length && job.ranges.every((range, index) =>
      range.startMs === ranges.value[index].startMs && range.endMs === ranges.value[index].endMs)
  })

  function invalidatePlayback() {
    previewRequest++
    state.previewing = false
    clearInterval(boundaryTimer)
    boundaryTimer = null
    if (restoreMetadata) video?.removeEventListener?.('loadedmetadata', restoreMetadata)
    restoreMetadata = null
  }
  function startBoundaryTimer() {
    clearInterval(boundaryTimer)
    boundaryTimer = setInterval(syncPlayback, 80)
  }
  function stopPreview() {
    invalidatePlayback()
    video?.pause()
  }
  function clearFrame() {
    if (state.frameUrl) objectUrls.revokeObjectURL(state.frameUrl)
    state.frameUrl = ''
    state.frameAtMs = null
  }
  const videoEvents = ['timeupdate', 'loadedmetadata', 'seeked', 'seeking', 'ended']
  function paused() {
    // Native media events are queued; a previous pause can arrive after a new play.
    if (video && !video.paused) return
    // stopPreview already invalidated its own queued pause; do not cancel a URL
    // refresh merely because that earlier pause event arrives during the request.
    if (state.previewing) invalidatePlayback()
  }
  function bindVideo(element) {
    if (video === element) return
    stopPreview()
    if (video) {
      videoEvents.forEach(event => video.removeEventListener?.(event, syncPlayback))
      video.removeEventListener?.('error', refreshPlayback)
      video.removeEventListener?.('pause', paused)
      if (restoreMetadata) video.removeEventListener?.('loadedmetadata', restoreMetadata)
      restoreMetadata = null
    }
    video = element
    videoEvents.forEach(event => video?.addEventListener?.(event, syncPlayback))
    video?.addEventListener?.('error', refreshPlayback)
    video?.addEventListener?.('pause', paused)
  }
  function close() {
    generation++
    importRequest++
    frameRequest++
    saveRequest++
    stopPreview()
    bindVideo(null)
    clearTimeout(exportTimer)
    clearTimeout(asrTimer)
    resetSelection()
    exportTimer = asrTimer = null
    clearFrame()
    playbackRefreshAttempts = 0
    refreshingPlayback = false
    Object.assign(state, initial())
  }
  function installTranscript(document, project = null) {
    // Validation also rejects unknown saved IDs rather than silently editing a different revision.
    const kept = project ? [...project.keptCueIds] : document.cues.map(cue => cue.id)
    buildClipRanges(document.cues, kept, document.durationMs)
    stopPreview()
    timelineRevision++
    editRevision++
    state.transcript = document
    state.project = project
    state.keptCueIds = kept
    state.dirty = !project
    saveRequest++
    state.saving = false
    state.exporting = false
    state.exportJob = null
    state.downloadUrl = ''
    clearTimeout(exportTimer)
    clearTimeout(asrTimer)
    state.transcribing = false
    resetSelection()
  }
  async function open(media) {
    close()
    const token = generation
    let shouldTranscribe = false
    let pendingTranscript = null
    currentSession = api.captureSession?.() || (() => true)
    Object.assign(state, { visible: true, loading: true, mediaId: media.id, filename: media.filename || `视频 ${media.id}` })
    // Playback is independent of transcription availability and AI analysis state.
    const playback = Promise.resolve().then(() => api.playback(media.id)).then(url => {
      if (active(token)) state.playbackUrl = url
    }).catch(error => { if (active(token)) state.playbackError = message(error) })
    try {
      const project = await api.project(media.id)
      if (!active(token)) return
      const document = await api.transcript(media.id, project?.transcriptId)
      if (!active(token)) return
      if (document?.state === 'COMPLETED') {
        installTranscript(document, project)
        // Recovery is independent of playback/export loading; do not submit a new model call.
        void restoreSelection()
      }
      else if (document) state.notice = document.state === 'FAILED' ? '时间轴生成失败，请导入 SRT / VTT 字幕继续。' : '时间轴尚未完成，可等待生成或导入字幕。'
      // Submit is idempotent: an existing queued/running document resumes polling.
      // Only the successful-upload entry requests a new automatic transcription.
      shouldTranscribe = media.autoTranscribe === true && !project && document?.state !== 'COMPLETED'
      if (!project && ['QUEUED', 'PENDING', 'PROCESSING', 'RUNNING'].includes(document?.state)) pendingTranscript = document
      if (project && api.latestExport) {
        const job = await api.latestExport(project.id)
        if (!active(token) || state.project?.id !== project.id) return
        state.exportJob = job
        if (job && !terminal(job)) pollExport(token, project.id, job.id)
      }
    } catch (error) {
      if (active(token)) state.error = message(error)
    } finally {
      await playback
      if (active(token)) state.loading = false
    }
    if (active(token) && shouldTranscribe) await transcribe()
    else if (active(token) && pendingTranscript) {
      beginTranscription()
      applyTranscription(pendingTranscript, token, timelineRevision)
    }
  }
  function changeSelection(ids) {
    stopPreview()
    state.keptCueIds = ids
    state.dirty = true
    state.notice = ''
    state.downloadUrl = ''
    editRevision++
  }
  function resetSelection() {
    selectionRequest++
    clearTimeout(selectionTimer)
    selectionTimer = null
    selectionSnapshot = null
    Object.assign(state, { selectionJob: null, selectionError: '', selectionSubmitting: false,
      selectionRestoring: false, selectionRestored: false, selectionApplied: false })
  }
  function selectionContext() {
    return { token: generation, timeline: timelineRevision, revision: editRevision,
      fingerprint: selectionFingerprint(), request: ++selectionRequest,
      mediaId: state.mediaId, transcriptId: state.transcript.id }
  }
  const selectionActive = context => active(context.token) && context.timeline === timelineRevision && context.request === selectionRequest
  function acceptSelection(job, context) {
    if (!selectionActive(context)) return false
    if (!job || String(job.mediaId) !== String(context.mediaId) || String(job.transcriptId) !== String(context.transcriptId) ||
      !job.id || !['QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'].includes(job.state)) throw new Error('推荐结果与当前视频或字幕版本不匹配，请重新生成。')
    if (job.state === 'COMPLETED') {
      if (!Array.isArray(job.keptCueIds) || !Array.isArray(job.selections)) throw new Error('推荐结果格式无效，请重新生成。')
      const known = new Set(state.transcript.cues.map(cue => cue.id))
      const selected = new Set(job.keptCueIds)
      if (selected.size !== job.keptCueIds.length || job.keptCueIds.some(id => !known.has(id)) ||
        job.selections.some(group => !Array.isArray(group.cueIds) || group.cueIds.some(id => !known.has(id) || !selected.has(id)))) {
        throw new Error('推荐包含当前字幕中不存在的句段，请重新生成。')
      }
      buildClipRanges(state.transcript.cues, job.keptCueIds, state.transcript.durationMs)
    }
    state.selectionJob = job
    state.selectionError = job.state === 'FAILED' ? `${job.errorMessage || '智能选段失败，请重试'}${job.errorCode ? `（${job.errorCode}）` : ''}` : ''
    if (!terminal(job)) pollSelection(context, job.id)
    return true
  }
  function pollSelection(context, jobId) {
    clearTimeout(selectionTimer)
    selectionTimer = setTimeout(async () => {
      if (!selectionActive(context) || state.selectionJob?.id !== jobId) return
      try {
        const job = await api.selectionStatus(jobId)
        if (!selectionActive(context) || state.selectionJob?.id !== jobId) return
        if (job?.id !== jobId) throw new Error('推荐任务不匹配，请恢复任务状态。')
        acceptSelection(job, context)
      } catch (error) { if (selectionActive(context)) state.selectionError = `推荐状态查询失败：${message(error)}。可恢复任务状态。` }
    }, 2000)
  }
  async function restoreSelection() {
    if (!state.transcript || !api.latestSelection || !state.visible || !currentSession()) return false
    clearTimeout(selectionTimer)
    const context = selectionContext()
    selectionSnapshot = context
    state.selectionRestoring = true
    state.selectionError = ''
    try {
      const job = await api.latestSelection(context.mediaId, context.transcriptId)
      if (!selectionActive(context)) return false
      state.selectionJob = null
      if (!job) return true
      state.selectionRestored = true
      state.selectionApplied = false
      state.selectionGoal = job.goal || ''
      return acceptSelection(job, context)
    } catch (error) { if (selectionActive(context)) state.selectionError = `恢复推荐失败：${message(error)}`; return false }
    finally { if (selectionActive(context)) state.selectionRestoring = false }
  }
  async function generateSelection() {
    if (!state.transcript || !state.visible || !currentSession() || state.importing || state.selectionSubmitting) return false
    const goal = state.selectionGoal.trim()
    if (!goal || goal.length > 500) { state.selectionError = '请输入 1～500 字的剪辑目标。'; return false }
    clearTimeout(selectionTimer)
    const context = selectionContext()
    selectionSnapshot = context
    Object.assign(state, { selectionSubmitting: true, selectionRestoring: false, selectionRestored: false,
      selectionApplied: false, selectionJob: null, selectionError: '' })
    try {
      const job = await api.createSelection({ mediaId: context.mediaId, transcriptId: context.transcriptId, goal })
      return acceptSelection(job, context)
    } catch (error) { if (selectionActive(context)) state.selectionError = message(error); return false }
    finally { if (selectionActive(context)) state.selectionSubmitting = false }
  }
  function applySelection({ confirmRestored = false } = {}) {
    // Auth sessions are not Vue reactive; recheck even when the computed button state is cached.
    if (!state.visible || !currentSession()) return false
    if (selectionConflict.value) { state.selectionError = '生成或恢复推荐后，当前草稿已有改动，请重新生成推荐。'; return false }
    if (!selectionCanApply.value || state.selectionRestored && !confirmRestored) return false
    changeSelection([...state.selectionJob.keptCueIds])
    state.selectionApplied = true
    state.notice = '推荐已应用到当前草稿。请预览检查，可继续手动微调，再保存或导出。'
    return true
  }
  function setKept(id, kept) {
    if (!state.transcript?.cues.some(cue => cue.id === id)) return
    const ids = new Set(state.keptCueIds)
    if (ids.has(id) === kept) return
    if (kept) ids.add(id)
    else ids.delete(id)
    changeSelection([...ids])
  }
  const selectAll = () => changeSelection(state.transcript?.cues.map(cue => cue.id) || [])
  const deselectAll = () => changeSelection([])
  async function save() {
    if (!state.transcript || state.saving || state.importing) return false
    const token = generation, timeline = timelineRevision, revision = editRevision, requestId = ++saveRequest
    const draft = { id: state.project?.id, mediaId: state.mediaId, transcriptId: state.transcript.id,
      version: state.project?.version || 0, keptCueIds: [...state.keptCueIds] }
    state.saving = true
    state.error = ''
    try {
      const project = await api.saveProject(draft)
      if (!active(token) || timeline !== timelineRevision) return false
      state.project = project
      state.dirty = revision !== editRevision
      state.notice = state.dirty ? '已保存之前的选段；当前改动尚未保存。' : '方案已保存，重新进入可继续编辑。'
      return !state.dirty
    } catch (error) {
      if (active(token) && timeline === timelineRevision) state.error = error.status === 409
        ? '方案已被更新。当前选段仍保留，请重新加载已保存方案后再编辑。' : message(error)
      return false
    } finally {
      if (active(token) && requestId === saveRequest) state.saving = false
    }
  }
  function seek(cue) {
    stopPreview()
    if (video) video.currentTime = cue.startMs / 1000
    state.currentTimeMs = cue.startMs
    state.currentCueId = cue.id
  }
  function syncPlayback() {
    if (!video || !state.visible) return
    let atMs = Math.round(video.currentTime * 1000)
    if (state.previewing) {
      const range = ranges.value.find(part => atMs < part.endMs)
      if (!range) stopPreview()
      else if (atMs < range.startMs) {
        atMs = range.startMs
        video.currentTime = atMs / 1000
      }
      if (video.ended) stopPreview()
    }
    state.currentTimeMs = atMs
    state.currentCueId = state.transcript?.cues.find(cue => cue.startMs <= atMs && atMs < cue.endMs)?.id || null
  }
  async function preview() {
    if (!ranges.value.length) { state.error = '请至少保留一段文字后再预览或导出。'; return false }
    if (!video || !state.playbackUrl) { state.error = '原视频尚未就绪，请等待加载或重新进入。'; return false }
    const token = generation, element = video
    stopPreview()
    const requestId = previewRequest
    element.currentTime = ranges.value[0].startMs / 1000
    state.previewing = true
    state.error = ''
    try {
      await element.play()
      if (!playbackActive(token, requestId, element) || !state.previewing) return false
      startBoundaryTimer()
      return true
    } catch (error) {
      if (playbackActive(token, requestId, element)) { stopPreview(); state.error = `无法播放原视频：${message(error)}` }
      return false
    }
  }
  async function refreshPlayback() {
    if (!state.visible || refreshingPlayback || playbackRefreshAttempts >= 1) {
      if (state.visible && playbackRefreshAttempts >= 1) state.playbackError = '视频无法播放，请检查文件格式或重新进入刷新播放地址。'
      return
    }
    const token = generation, element = video, position = video?.currentTime || 0
    const wasPlaying = video && !video.paused
    const wasPreviewing = state.previewing
    refreshingPlayback = true
    playbackRefreshAttempts++
    stopPreview()
    const requestId = previewRequest
    try {
      const url = await api.playback(state.mediaId)
      if (!playbackActive(token, requestId, element)) return
      state.playbackUrl = url
      if (element) {
        element.src = url
        const restore = () => {
          element.removeEventListener?.('loadedmetadata', restore)
          if (restoreMetadata === restore) restoreMetadata = null
          if (!playbackActive(token, requestId, element)) return
          element.currentTime = position
          if (wasPlaying) {
            state.previewing = wasPreviewing
            syncPlayback()
            element.play().then(() => {
              if (playbackActive(token, requestId, element) && state.previewing) startBoundaryTimer()
            }).catch(() => { if (playbackActive(token, requestId, element)) stopPreview() })
          }
        }
        restoreMetadata = restore
        element.addEventListener?.('loadedmetadata', restore, { once: true })
        element.load?.()
      }
    } catch (error) { if (playbackActive(token, requestId, element)) state.playbackError = message(error) }
    finally {
      if (active(token)) {
        refreshingPlayback = false
        // A superseded refresh did not apply its URL; a later playback error
        // may request a fresh one instead of consuming the sole retry forever.
        if (requestId !== previewRequest) playbackRefreshAttempts = 0
      }
    }
  }
  async function importSubtitles(format, content) {
    if (!content?.trim()) { state.error = '请选择包含时间标记的 SRT 或 VTT 字幕。'; return false }
    const token = generation, requestId = ++importRequest
    state.importing = true
    state.error = ''
    try {
      const document = await api.importTranscript(state.mediaId, format, content)
      if (!active(token) || requestId !== importRequest) return false
      installTranscript(document)
      state.notice = '字幕已导入为新时间轴，请保存新方案。旧方案仍保留原字幕版本。'
      return true
    } catch (error) { if (active(token) && requestId === importRequest) state.error = message(error); return false }
    finally { if (active(token) && requestId === importRequest) state.importing = false }
  }
  async function captureFrame(atMs = state.currentTimeMs) {
    const token = generation, requestId = ++frameRequest
    const position = Math.max(0, Math.round(atMs))
    state.frameLoading = true
    state.frameError = ''
    try {
      const blob = await api.frame(state.mediaId, position)
      if (!active(token) || requestId !== frameRequest) return false
      clearFrame()
      state.frameUrl = objectUrls.createObjectURL(blob)
      state.frameAtMs = position
      return true
    } catch (error) { if (active(token) && requestId === frameRequest) state.frameError = message(error); return false }
    finally { if (active(token) && requestId === frameRequest) state.frameLoading = false }
  }
  function beginTranscription() {
    state.transcribing = true
    state.error = ''
    state.notice = '正在生成带时间的句段文字，请稍候；完成后可按句选段，也可先导入字幕。'
  }
  function applyTranscription(document, token, timeline) {
    if (!active(token) || timeline !== timelineRevision) return
    if (document?.state === 'COMPLETED') {
      installTranscript(document)
      state.notice = '带时间的转写已生成，请核对时间与文字并保存方案。'
    } else if (document?.state === 'FAILED') {
      state.transcribing = false
      state.notice = ''
      state.error = `时间戳转写不可用${document.errorCode ? `（${document.errorCode}）` : ''}。请导入 SRT / VTT 字幕，或联系管理员配置时间戳转写服务。`
    } else {
      asrTimer = setTimeout(async () => {
        if (!active(token) || timeline !== timelineRevision) return
        try { applyTranscription(await api.transcriptStatus(state.mediaId), token, timeline) }
        catch (error) { if (active(token) && timeline === timelineRevision) { state.transcribing = false; state.notice = ''; state.error = message(error) } }
      }, 2000)
    }
  }
  async function transcribe() {
    if (!state.visible || state.loading || state.importing || state.transcribing) return
    const token = generation, timeline = timelineRevision
    beginTranscription()
    try { applyTranscription(await api.transcribe(state.mediaId), token, timeline) }
    catch (error) {
      if (active(token) && timeline === timelineRevision) {
        state.transcribing = false
        state.notice = ''
        state.error = `${message(error)}。可导入 SRT / VTT 字幕继续；自动转写需要已配置的时间戳服务。`
      }
    }
  }
  function pollExport(token, projectId, jobId) {
    clearTimeout(exportTimer)
    exportTimer = setTimeout(async () => {
      if (!active(token) || state.project?.id !== projectId || state.exportJob?.id !== jobId) return
      try {
        const job = await api.exportStatus(jobId)
        if (!active(token) || state.project?.id !== projectId || state.exportJob?.id !== jobId) return
        state.exportJob = job
        if (!terminal(job)) pollExport(token, projectId, jobId)
      } catch (error) { if (active(token) && state.project?.id === projectId) state.exportError = `导出状态查询失败：${message(error)}。可关闭后重新进入查看。` }
    }, 2000)
  }
  async function exportVideo() {
    if (!ranges.value.length) { state.error = '请至少保留一段文字后再预览或导出。'; return false }
    if (state.exporting || state.importing) return false
    const token = generation, timeline = timelineRevision, revision = editRevision
    state.exporting = true
    state.exportError = ''
    try {
      if ((!state.project || state.dirty) && !(await save())) return false
      if (!active(token) || timeline !== timelineRevision || revision !== editRevision) return false
      const projectId = state.project.id, version = state.project.version
      const job = await api.createExport({ projectId, projectVersion: version, preset: 'mp4' })
      if (!active(token) || timeline !== timelineRevision || state.project?.id !== projectId) return false
      state.exportJob = job
      state.downloadUrl = ''
      if (!terminal(job)) pollExport(token, projectId, job.id)
      return true
    } catch (error) { if (active(token) && timeline === timelineRevision) state.exportError = message(error); return false }
    finally { if (active(token)) state.exporting = false }
  }
  async function downloadExport() {
    if (state.exportJob?.state !== 'COMPLETED' || !exportIsCurrent.value) return
    const token = generation, jobId = state.exportJob.id, revision = editRevision
    state.downloading = true
    state.exportError = ''
    try {
      const url = await api.download(jobId)
      if (!active(token) || state.exportJob?.id !== jobId || revision !== editRevision) return
      state.downloadUrl = url
    } catch (error) { if (active(token)) state.exportError = message(error) }
    finally { if (active(token)) state.downloading = false }
  }
  return { state, ranges, selectedDurationMs, exportIsCurrent, selectionBusy, selectionConflict, selectionCanApply,
    generateSelection, restoreSelection, applySelection, open, close, bindVideo, setKept, selectAll, deselectAll,
    save, seek, syncPlayback, preview, stopPreview, refreshPlayback, importSubtitles, captureFrame, transcribe, exportVideo, downloadExport }
}
