<template>
  <Teleport to="body">
    <dialog ref="dialog" class="text-editor" aria-labelledby="text-editor-title" @cancel.prevent="requestClose">
      <div class="te-shell">
        <header class="te-header">
          <div class="te-heading"><span class="te-eyebrow">原视频 · 按文字选段</span><h2 id="text-editor-title">文本剪辑</h2><p :title="filename">{{ filename || `视频 ${mediaId}` }}</p></div>
          <button ref="closeButton" class="te-close" type="button" aria-label="关闭文本剪辑" @click="requestClose">×</button>
        </header>

        <div v-if="state.loading" class="te-message" role="status">正在加载原视频与已保存方案…</div>
        <div v-if="state.error" class="te-message te-error" role="alert">
          <span>{{ state.error }}</span><button v-if="state.error.includes('重新加载')" type="button" @click="reload">重新加载已保存方案</button>
        </div>
        <p v-if="state.notice" class="te-message" role="status">{{ state.notice }}</p>

        <div class="te-columns">
          <section class="te-source" aria-labelledby="te-source-title">
            <div class="te-section-label"><h3 id="te-source-title">原视频</h3><span>源文件保持完整</span></div>
            <div class="te-player">
              <video v-if="state.playbackUrl" ref="player" :src="state.playbackUrl" controls playsinline preload="metadata" aria-label="原视频播放器" />
              <p v-else>{{ state.playbackError || '正在获取播放地址…' }}</p>
            </div>
            <p v-if="state.playbackError" class="te-inline-error" role="alert">{{ state.playbackError }}</p>
            <div class="te-preview-controls">
              <button v-if="!state.previewing" class="te-primary" type="button" :disabled="!ranges.length || !state.playbackUrl || state.loading" @click="editor.preview">连续预览选段</button>
              <button v-else class="te-primary" type="button" @click="editor.stopPreview">停止选段预览</button>
              <span class="te-clock">{{ time(state.currentTimeMs) }}</span>
            </div>
            <p class="te-help">预览会在原视频上跳过已删除的句段，切换处可能短暂停顿。导出后生成独立的 MP4。</p>

            <section class="te-frame" aria-labelledby="te-frame-title">
              <div class="te-section-label"><h3 id="te-frame-title">该时间的画面</h3><button type="button" :disabled="state.frameLoading || !state.playbackUrl" @click="editor.captureFrame()">{{ state.frameLoading ? '正在截取…' : '查看当前画面' }}</button></div>
              <figure v-if="state.frameUrl"><img :src="state.frameUrl" :alt="`原视频 ${time(state.frameAtMs)} 的实际画面`" /><figcaption>{{ time(state.frameAtMs) }} · 原视频截图</figcaption></figure>
              <p v-else class="te-help">先播放或点击文字定位，再查看该时间的实际画面。</p>
              <p v-if="state.frameError" class="te-inline-error" role="alert">{{ state.frameError }}</p>
            </section>
          </section>

          <section class="te-transcript" aria-labelledby="te-transcript-title">
            <div class="te-transcript-top">
              <div class="te-section-label"><h3 id="te-transcript-title">时间轴文本</h3><span v-if="state.transcript">{{ state.transcript.cues.length }} 段 · 字幕版本 {{ state.transcript.revision }}</span></div>
              <div class="te-import-actions">
                <button type="button" title="分析原视频完整内容；当前剪辑方案会先保存" :disabled="!state.transcript || state.loading || state.transcribing || state.importing || state.saving" @click="analyze">总结与理解</button>
                <label class="te-file-button" :class="{ 'te-disabled': state.importing || state.loading }">
                  {{ state.importing ? '正在导入…' : '导入 SRT / VTT' }}
                  <input type="file" accept=".srt,.vtt" :disabled="state.importing || state.loading" @change="importFile" />
                </label>
                <button type="button" :disabled="state.transcribing || state.loading || state.importing" @click="editor.transcribe">{{ state.transcribing ? '正在生成时间轴…' : '自动转写时间轴' }}</button>
              </div>
              <p class="te-help">总结使用原视频的完整转写，选段只影响成片。也可导入匹配的 SRT / VTT 字幕；重新导入会开始一个新方案。</p>
              <section v-if="state.transcript" class="te-smart" aria-labelledby="te-smart-title">
                <div class="te-section-label"><h3 id="te-smart-title">AI 智能选段</h3><span>根据文字推荐 · 保持原顺序</span></div>
                <label class="te-smart-label" for="te-smart-goal">剪辑目标</label>
                <textarea id="te-smart-goal" v-model="state.selectionGoal" maxlength="500" rows="2" placeholder="例如：保留完整问答，去掉等待与无关内容，尽量保留前后文。" :disabled="selectionBusy || state.importing" aria-describedby="te-smart-help" />
                <div class="te-smart-actions">
                  <button class="te-primary" type="button" :disabled="selectionBusy || !state.selectionGoal.trim() || state.loading || state.importing" @click="editor.generateSelection">{{ state.selectionSubmitting ? '正在提交…' : selectionBusy ? '正在生成推荐…' : state.selectionJob || state.selectionError ? '重新生成推荐' : '生成推荐' }}</button>
                  <button type="button" :disabled="state.selectionRestoring || state.selectionSubmitting || state.importing" @click="editor.restoreSelection">{{ state.selectionRestoring ? '正在恢复…' : '恢复任务状态' }}</button>
                  <span>{{ state.selectionGoal.length }} / 500</span>
                </div>
                <p id="te-smart-help" class="te-help">仅根据字幕文字判断，不识别画面。推荐需要人工核对；应用后仍可微调，保存与导出由你确认。</p>
                <p v-if="state.selectionError" class="te-inline-error" role="alert">{{ state.selectionError }}</p>
                <div v-if="state.selectionJob" class="te-smart-result" aria-live="polite">
                  <p><strong>{{ selectionLabel }}</strong><span v-if="state.selectionJob.totalBatches"> · {{ state.selectionJob.completedBatches || 0 }} / {{ state.selectionJob.totalBatches }} 批</span></p>
                  <p class="te-help">任务目标：{{ state.selectionJob.goal }}</p>
                  <template v-if="state.selectionJob.state === 'COMPLETED'">
                    <p v-if="!state.selectionJob.keptCueIds.length" class="te-help">未找到符合目标的句段。当前草稿保持原样，可修改目标后重新生成。</p>
                    <template v-else>
                      <p class="te-smart-duration">建议保留 {{ state.selectionJob.keptCueIds.length }} 段 · 按时间轴计算 {{ duration(state.selectionJob.durationMs) }}</p>
                      <ol class="te-smart-groups" aria-label="推荐句段与理由">
                        <li v-for="(group, index) in state.selectionJob.selections" :key="index">
                          <p>{{ group.reason || '与剪辑目标相关' }}</p>
                          <button v-for="cue in selectionCues(group)" :key="cue.id" type="button" :aria-label="`回看推荐句段 ${time(cue.startMs)}：${cue.text}`" @click="editor.seek(cue)"><span>{{ time(cue.startMs) }}</span> {{ cue.text }}</button>
                        </li>
                      </ol>
                      <p v-if="state.selectionApplied" class="te-help" role="status">已应用到草稿，可在下方微调，再连续预览、保存与导出。</p>
                      <p v-else-if="selectionConflict" class="te-inline-error" role="status">生成或恢复后草稿已有改动。为保护手动选段，请重新生成推荐。</p>
                      <p v-else-if="state.selectionRestored" class="te-help">已恢复之前的任务。原草稿无法核对，请查看推荐，再确认替换当前草稿。</p>
                      <button type="button" :disabled="!selectionCanApply" @click="editor.applySelection({ confirmRestored: state.selectionRestored })">{{ state.selectionApplied ? '推荐已应用' : state.selectionRestored ? '确认用恢复推荐替换当前草稿' : '应用推荐到当前草稿' }}</button>
                    </template>
                  </template>
                </div>
              </section>
              <div v-if="state.transcript" class="te-selection-controls"><button type="button" @click="editor.selectAll">全部保留</button><button type="button" @click="editor.deselectAll">全部删除</button><span>点击文字定位 · 取消勾选即删除整段</span></div>
            </div>

            <div v-if="!state.transcript && !state.loading && state.transcribing" class="te-empty" role="status"><h3>正在转写原视频…</h3><p>完成后将在这里显示带时间的句段，所有句段默认保留。</p></div>
            <div v-else-if="!state.transcript && !state.loading" class="te-empty">
              <span aria-hidden="true" class="te-empty-symbol">[ 00:00 ]</span><h3>需要带时间的转写或字幕</h3>
              <p>导入 SRT / VTT 后，可以点击文字回看、删除整段，再保存与导出。</p>
              <p>自动转写需要已配置的时间戳服务；普通纯文本无法用于准确选段。</p>
            </div>

            <ol v-else-if="state.transcript" class="te-cues" aria-label="字幕句段">
              <li v-for="cue in state.transcript.cues" :key="cue.id" class="te-cue" :class="{ 'te-cue-deleted': !kept.has(cue.id), 'te-cue-current': state.currentCueId === cue.id }">
                <input type="checkbox" :checked="kept.has(cue.id)" :aria-label="`保留 ${time(cue.startMs)} 的句段：${cue.text}`" @change="editor.setKept(cue.id, $event.target.checked)" />
                <button class="te-cue-seek" type="button" :aria-label="`定位到 ${time(cue.startMs)}：${cue.text}`" @click="editor.seek(cue)">
                  <span class="te-cue-time">{{ time(cue.startMs) }} — {{ time(cue.endMs) }} <span v-if="state.currentCueId === cue.id" class="te-current-label">播放位置</span></span>
                  <span class="te-cue-text">{{ cue.text }}</span>
                </button>
                <button class="te-cue-toggle" type="button" :aria-label="`${kept.has(cue.id) ? '删除' : '恢复'} ${time(cue.startMs)} 的句段`" @click="editor.setKept(cue.id, !kept.has(cue.id))">{{ kept.has(cue.id) ? '删除' : '恢复' }}</button>
              </li>
            </ol>
          </section>
        </div>

        <footer class="te-footer">
          <div class="te-selection-summary"><strong>{{ duration(selectedDurationMs) }}</strong><span>保留 {{ state.keptCueIds.length }} 段<span v-if="state.transcript"> / {{ state.transcript.cues.length }} 段</span> · {{ ranges.length }} 个连续区间</span><small>{{ state.dirty ? '有未保存的改动' : state.project ? `已保存 · 方案版本 ${state.project.version}` : '导入字幕后开始选段' }}</small></div>
          <div class="te-footer-actions"><button type="button" :disabled="!state.transcript || state.loading || state.saving || state.importing" @click="editor.save">{{ state.saving ? '正在保存…' : '保存方案' }}</button><button class="te-primary" type="button" :disabled="!ranges.length || state.loading || state.exporting || state.saving || state.importing" @click="editor.exportVideo">{{ state.exporting ? '正在提交…' : '导出 MP4' }}</button></div>
        </footer>
        <div v-if="state.transcript && !ranges.length" class="te-empty-selection" role="status">当前为空草稿，可以保存；请恢复至少一段后再预览或导出。</div>
        <section v-if="state.exportJob || state.exportError" class="te-export-status" aria-label="导出进度" aria-live="polite">
          <p v-if="state.exportJob"><strong>{{ exportLabel }}</strong> · 方案版本 {{ state.exportJob.projectVersion }}<span v-if="!exportIsCurrent">（之前保存的选段，当前改动尚未导出）</span><span v-else-if="state.exportJob.projectVersion !== state.project?.version">（相同选段，已复用成片）</span><span v-if="state.exportJob.errorCode"> · {{ state.exportJob.errorCode }}</span></p>
          <p v-if="state.exportError" class="te-inline-error" role="alert">{{ state.exportError }}</p>
          <button v-if="exportIsCurrent && state.exportJob?.state === 'COMPLETED' && !state.downloadUrl" type="button" :disabled="state.downloading" @click="editor.downloadExport">{{ state.downloading ? '正在获取下载地址…' : '获取成片下载地址' }}</button>
          <template v-if="state.downloadUrl"><a :href="state.downloadUrl" target="_blank" rel="noopener noreferrer" download>下载 MP4 成片 ↗</a><button type="button" :disabled="state.downloading" @click="editor.downloadExport">刷新下载地址</button></template>
        </section>
        <p v-if="state.dirty" class="te-close-hint">关闭前请保存方案，未保存的改动会丢失。</p>
      </div>
    </dialog>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useTextVideoEditor } from '../useTextVideoEditor.js'
import '../textVideoEditor.css'

const props = defineProps({ mediaId: { type: [Number, String], required: true }, filename: { type: String, default: '' }, autoTranscribe: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'analyze'])
const dialog = ref(null), closeButton = ref(null), player = ref(null)
const editor = useTextVideoEditor()
const { state, ranges, selectedDurationMs, exportIsCurrent, selectionBusy, selectionConflict, selectionCanApply } = editor
const kept = computed(() => new Set(state.keptCueIds))
const selectionLabel = computed(() => ({ QUEUED: '等待智能选段', PROCESSING: '正在按目标分析字幕', COMPLETED: '推荐已生成', FAILED: '推荐失败，可重新生成' }[state.selectionJob?.state] || '正在查询推荐'))
const selectionCues = group => state.transcript?.cues.filter(cue => group.cueIds.includes(cue.id)) || []
const exportLabel = computed(() => ({ QUEUED: '等待导出', PENDING: '等待导出', PROCESSING: '正在生成 MP4', RUNNING: '正在生成 MP4', COMPLETED: 'MP4 已生成', FAILED: '导出失败，可以重试', CANCELLED: '导出已取消' }[state.exportJob?.state] || '正在处理导出'))
const time = ms => {
  const value = Math.max(0, Math.round(ms || 0)), seconds = Math.floor(value / 1000)
  return `${String(Math.floor(seconds / 3600)).padStart(2, '0')}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}.${String(value % 1000).padStart(3, '0')}`
}
const duration = ms => `${Math.floor(ms / 60000)} 分 ${((ms % 60000) / 1000).toFixed(1)} 秒`
let fileRequest = 0
let previousFocus = null
const reload = () => editor.open({ id: props.mediaId, filename: props.filename, autoTranscribe: props.autoTranscribe })
function requestClose() { editor.close(); emit('close') }
async function analyze() {
  if (!state.transcript || state.loading || state.transcribing || state.importing || state.saving) return
  const mediaId = props.mediaId
  if (state.dirty && !(await editor.save())) return
  if (!state.visible || mediaId !== props.mediaId) return
  emit('analyze', mediaId)
}
async function importFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!/\.(srt|vtt)$/i.test(file.name)) { state.error = '请选择 .srt 或 .vtt 字幕文件。'; return }
  if (file.size > 2 * 1024 * 1024) { state.error = '字幕文件超过 2 MB，请拆分或缩小后重试。'; return }
  const requestId = ++fileRequest, mediaId = props.mediaId
  try {
    const content = await file.text()
    if (requestId !== fileRequest || mediaId !== props.mediaId || !state.visible) return
    await editor.importSubtitles(file.name.toLowerCase().endsWith('.vtt') ? 'VTT' : 'SRT', content)
  } catch (error) { if (requestId === fileRequest && state.visible) state.error = `字幕文件读取失败：${error.message}` }
}
watch(() => props.mediaId, () => { fileRequest++; reload() }, { immediate: true })
watch(player, element => editor.bindVideo(element), { flush: 'post' })
onMounted(async () => {
  previousFocus = document.activeElement
  dialog.value?.showModal()
  await nextTick()
  closeButton.value?.focus()
})
onUnmounted(() => {
  fileRequest++
  editor.close()
  dialog.value?.close()
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>
