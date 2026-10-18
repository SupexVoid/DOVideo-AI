<template>
  <div class="app-stage">
    <header class="navbar">
      <div class="nav-content">
        <div class="brand">
          <span class="brand-symbol" aria-hidden="true"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="5"/><path d="m10 8 6 4-6 4Z"/></svg></span>
          <span class="brand-name">DoVideo-AI</span>
        </div>
        <nav class="workspace-nav" aria-label="工作台导航">
          <a href="#import" class="nav-current">视频工作台</a>
          <a v-if="list.length" href="#library">视频资料库</a>
        </nav>

        <div class="nav-controls">
          <button v-if="!currentUser" class="auth-btn" @click="openAuthModal">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
            </span>
            登录 / 注册
          </button>

          <div v-else class="user-profile">
            <span class="user-avatar" aria-hidden="true">{{ currentUser.nickname?.slice(0, 1) }}</span>
            <span class="user-name">{{ currentUser.nickname }}</span>
            <button class="logout-btn" @click="logout" title="退出登录" aria-label="退出登录">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
            </button>
          </div>

          <div class="status-pill" :class="{ 'is-active': uploading }" role="status" aria-live="polite">
            <div class="status-dot"></div>
            <span class="status-text">{{ systemStatusText }}</span>
          </div>
        </div>
      </div>
    </header>

    <main class="main-container">
      <div v-if="DEMO_MODE" class="demo-notice" role="note">
        <strong>界面演示</strong><span>当前为项目内置示例，视频、分析结果与评估数据均非本次实际处理结果。</span>
        <a href="/">返回工作台</a>
      </div>
      <section id="import" class="hero-section">
        <div class="workspace-heading">
          <div><h1 class="slogan-main">视频工作台</h1>
          <p class="slogan-sub">从一段视频开始，整理文字、定位证据，生成有依据的 AI 内容。</p></div>
          <a v-if="!DEMO_MODE" class="demo-link" href="?demo">查看界面演示 <span aria-hidden="true">↗</span></a>
        </div>

        <div class="upload-wrapper">
          <input
              type="file"
              id="file-input"
              @change="handleFileChange"
              accept=".mp4,.mov,.mkv,.avi,.webm,.m4v"
              hidden
          />

          <div
              class="upload-magnet"
              :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
              @dragenter.prevent="handleDragEnter"
              @dragover.prevent="isDragOver = true"
              @dragleave.prevent="handleDragLeave"
              @drop.prevent="handleDrop"
          >
            <div class="split-container" v-if="!uploading">

              <label for="file-input" class="skew-pane pane-local">
                <div class="pane-content unskew">
                  <div class="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                  </div>
                  <span class="magnet-title">导入本地视频</span>
                  <span class="magnet-desc">{{ isDragOver ? '松开即可上传' : '点击选择，或将视频拖到这里' }}</span>
                  <span class="upload-formats">MP4、MOV、MKV、AVI、WebM、M4V</span>
                </div>
              </label>

              <div class="split-gap"></div>

              <div class="skew-pane pane-url">
                <div class="pane-content unskew">
                  <div class="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"></path></svg>
                  </div>
                  <span class="magnet-title">通过链接导入</span>
                  <span class="magnet-desc">粘贴视频地址，站点支持取决于下载器与访问权限。</span>

                  <div class="url-input-box" @click.stop>
                    <input
                        v-model="videoUrl"
                        type="text"
                        inputmode="url"
                        autocomplete="off"
                        spellcheck="false"
                        placeholder="https:// 视频链接"
                        aria-label="视频链接"
                        :disabled="uploading"
                        @keyup.enter="handleUrlUpload"
                    />
                    <button class="url-go-btn" :disabled="uploading" @click="handleUrlUpload" aria-label="解析视频链接">
                      <span>导入</span><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                    </button>
                  </div>
                </div>
              </div>

            </div>

            <div class="magnet-content busy" v-else>
              <div class="quantum-loader"></div>
              <span class="busy-text">{{ uploadProgress.label }}</span>
              <span v-if="uploadProgress.filename" class="busy-file">{{ uploadProgress.filename }}</span>
              <div
                  v-if="uploadProgress.percent !== null"
                  class="upload-progress"
                  role="progressbar"
                  aria-label="视频上传进度"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  :aria-valuenow="uploadProgress.percent"
              >
                <span :style="{ width: `${uploadProgress.percent}%` }"></span>
              </div>
              <span v-if="uploadProgress.detail" class="busy-stat" aria-live="polite">{{ uploadProgress.detail }}</span>
              <span v-if="uploadProgress.warning" class="busy-warning" role="status">{{ uploadProgress.warning }}</span>
              <div v-if="uploadAbort" class="busy-actions">
                <button type="button" @click="cancelUpload">取消上传</button>
              </div>
            </div>

          </div>

          <div v-if="resumableFile && !uploading" class="upload-resume" role="status">
            <span>{{ resumeHint }}</span>
            <button type="button" @click="resumeUpload">继续上传</button>
            <button type="button" @click="discardResumableUpload">重新开始</button>
          </div>
        </div>
        <transition name="toast-pop">
          <div
              v-if="message"
              class="notification-bar"
              :class="{ 'error': messageIsError }"
              :role="messageIsError ? 'alert' : 'status'"
              :aria-live="messageIsError ? 'assertive' : 'polite'"
              :title="messageIsError ? '点击关闭这条提示' : null"
              @click="dismissMessage"
          >
            {{ message }}
          </div>
        </transition>
      </section>

      <section v-if="list.length > 0" id="library" class="workspace-section">
        <div class="section-header">
          <div class="library-title">
            <h2>视频资料库</h2>
            <div class="count-chip">{{ list.length }} 个视频</div>
          </div>
          <label class="library-search">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
            <input v-model="searchQuery" type="search" placeholder="搜索视频名称" aria-label="搜索视频名称" />
          </label>
        </div>
        <div class="card-grid">
          <div v-for="item in visibleList" :key="item.id" class="project-card">

            <button
                class="delete-btn"
                :disabled="deletingId === item.id"
                :title="deletingId === item.id ? '正在删除…' : '删除视频'"
                :aria-label="`删除 ${item.filename}`"
                @click.stop="deleteItem(item)"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="3 6 5 6 21 6"></polyline><path d="M19 6l-1 14H6L5 6"></path><path d="M8 6V4h8v2"></path>
              </svg>
            </button>
            <div class="card-meta">
              <div class="meta-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
              </div>
              <div class="meta-info">
                <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
                <div class="meta-tags">
                  <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
                  <span
                      class="status-indicator"
                      :class="cardStatusClass(item)"
                      :title="cardStatusTitle(item)"
                  >
                    {{ cardStatusLabel(item) }}
                  </span>
                </div>
              </div>
            </div>

            <div class="action-dock">
              <button
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  :title="actionTitle(item, '下载音频')"
                  @click="downloadAudio(item)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
                </span>
                <span class="item-label">下载音频</span>
              </button>

              <button
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  :title="actionTitle(item, '提取文字')"
                  @click="transcribe(item.id)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                </span>
                <span class="item-label">提取文字</span>
              </button>

              <button
                  class="dock-item ai-core"
                  :disabled="item.status !== 'COMPLETED'"
                  :title="actionTitle(item, '打开 Video Agent')"
                  @click="openAgent(item)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
                </span>
                <div class="label-group">
                  <span class="item-label">Video Agent</span>
                </div>
              </button>
            </div>
          </div>
        </div>
        <div v-if="visibleList.length === 0" class="library-empty">
          <p>没有找到“{{ searchQuery }}”</p>
          <button type="button" @click="searchQuery = ''">清除搜索</button>
        </div>
      </section>

      <div v-if="currentUser && (listError || !list.length)" class="library-empty" role="status">
        <p>{{ listLoading ? '正在加载视频资料库…' : listError || '还没有视频，上传本地文件或粘贴视频链接即可开始分析。' }}</p>
        <button v-if="listError" type="button" :disabled="listLoading" @click="fetchList({ notify: true })">重新加载</button>
      </div>

      <div class="sidebar-backdrop" v-if="sidebar.visible" @click="closeSidebar"></div>
      <div
          ref="sidebarPanel"
          class="sidebar-panel"
          :class="{ 'is-open': sidebar.visible }"
          :inert="!sidebar.visible"
          role="dialog"
          aria-modal="true"
          tabindex="-1"
          :aria-label="sidebar.title || '任务详情'"
          @keydown="trapPanelFocus($event, sidebarPanel)"
      >
        <div class="sidebar-header">
          <div class="sidebar-title">
            <span class="icon" v-if="sidebar.type === 'ai'">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path><path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path></svg>
            </span>
            <span class="icon" v-else>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
            </span>
            {{ sidebar.title }}
          </div>
          <button class="close-btn" @click="closeSidebar" aria-label="关闭分析面板">×</button>
        </div>
        <div v-if="DEMO_MODE" class="detail-demo-notice" role="note">内置示例预览 · 未调用后端或 AI 服务，以下内容不代表真实处理结果。</div>
        <div ref="sidebarBody" class="sidebar-body" :class="{ 'has-video': sidebar.type === 'ai' }">
          <aside v-if="sidebar.type === 'ai'" class="detail-media">
          <h2 class="detail-section-title">视频与证据</h2>
          <div class="video-evidence">
            <video
                v-if="sidebar.playbackUrl"
                ref="videoPlayer"
                :src="sidebar.playbackUrl"
                controls
                playsinline
                preload="metadata"
                @error="handlePlaybackError"
            ></video>
            <div v-else-if="DEMO_MODE" class="video-demo-placeholder">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true"><rect x="2" y="4" width="20" height="16" rx="3"/><path d="m10 9 5 3-5 3Z"/></svg>
              <strong>原视频预览区</strong><span>内置示例不提供可播放视频</span>
            </div>
            <div v-else-if="sidebar.playbackLoading" class="video-evidence-loading">正在载入原视频...</div>
            <div v-else-if="sidebar.playbackError" class="video-evidence-error" role="alert">
              <span>{{ sidebar.playbackError }}</span>
              <button type="button" @click="retryPlayback">重新加载</button>
            </div>
            <p v-if="sidebar.playbackUrl">点击分析结果中的时间戳，可跳转到对应画面</p>
          </div>
          <p class="media-context">分析结论中的时间戳与原视频关联，便于回到上下文核对内容。</p>
          <div class="media-source"><span>当前分析目标</span><p>{{ sidebar.goal }}</p></div>
          </aside>
          <div class="detail-content">
          <div v-if="sidebar.type === 'ai' && sidebar.mode === 'compose'" class="agent-composer">
            <h2 class="detail-section-title">设置分析任务</h2>
            <p class="agent-caption">分析模式</p>
            <div class="goal-presets agent-mode-row">
              <button
                  v-for="m in analysisModes"
                  :key="m.value"
                  :class="{ active: sidebar.analysisMode === m.value }"
                  :aria-pressed="sidebar.analysisMode === m.value"
                  @click="sidebar.analysisMode = m.value"
              >
                <strong>{{ m.title }}</strong>
                <span>{{ m.description }}</span>
              </button>
            </div>
            <p class="agent-caption">你希望从视频中得到什么？</p>
            <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
            <textarea
                v-model="sidebar.goal"
                aria-label="分析目标"
                maxlength="500"
                placeholder="例如：梳理核心观点，给出带时间戳的证据和可执行建议（Ctrl / ⌘ + Enter 提交）"
                @keydown.ctrl.enter.prevent="submitAgent"
                @keydown.meta.enter.prevent="submitAgent"
            ></textarea>
            <p v-if="sidebar.goal.length > 400" class="field-counter">已输入 {{ sidebar.goal.length }} / 500 字</p>
            <div class="goal-presets">
              <button
                  v-for="preset in goalPresets"
                  :key="preset.title"
                  :class="{ active: sidebar.goal === preset.prompt }"
                  :aria-pressed="sidebar.goal === preset.prompt"
                  @click="sidebar.goal = preset.prompt"
              >
                <strong>{{ preset.title }}</strong>
                <span>{{ preset.description }}</span>
              </button>
            </div>
            <button class="agent-run-btn" :disabled="!sidebar.goal.trim()" @click="submitAgent">
              {{ sidebar.error ? '重新分析' : '开始分析' }}
            </button>
          </div>

          <div v-else-if="sidebar.loading" class="agent-running">
            <div class="loading-state">
              <div class="quantum-loader small"></div>
              <p aria-live="polite">{{ loadingHeadline }}</p>
              <p v-if="sidebar.streamOffline" class="stream-offline" role="status">
                连接中断，正在自动重连（第 {{ sidebar.streamRetry }} 次）· 任务仍在服务端继续
              </p>
              <p class="loading-hint">可以关闭本面板，任务会在后台继续，完成后会通知你</p>
            </div>
            <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
              <span class="meta-label">任务计划</span>
              <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
            </div>
            <div v-if="traceStages.length" class="agent-meta-block">
              <span class="meta-label">已完成阶段</span>
              <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}</span></div>
            </div>
          </div>

          <div v-else>
            <div v-if="sidebar.type === 'ai'">
              <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
              <h2 class="detail-section-title result-heading">AI 分析结果</h2>
              <div class="result-actions">
                <button type="button" @click="startNewAnalysis">更换产物</button>
                <button type="button" :disabled="!sidebar.content" @click="copyResult">复制结果</button>
                <button type="button" :disabled="!sidebar.content" @click="downloadResult">导出 Markdown</button>
              </div>
              <div class="evidence-search">
                <div class="evidence-search-form">
                  <input
                      v-model="sidebar.evidenceQuery"
                      aria-label="视频证据检索"
                      maxlength="500"
                      placeholder="定位 PPT、字幕、代码或某段讲解"
                      @keyup.enter="searchEvidence"
                  />
                  <button type="button" :disabled="sidebar.evidenceLoading || !sidebar.evidenceQuery.trim()" @click="searchEvidence">
                    {{ sidebar.evidenceLoading ? '检索中' : '定位证据' }}
                  </button>
                </div>
                <p v-if="sidebar.evidenceError" class="evidence-search-error" aria-live="polite">{{ sidebar.evidenceError }}</p>
                <div v-if="sidebar.evidenceResults.length" class="evidence-search-results" aria-live="polite">
                  <button
                      v-for="hit in sidebar.evidenceResults"
                      :key="`${hit.startMs}-${hit.endMs}`"
                      type="button"
                      :title="hit.snippet || '该时间段暂无可展示文本'"
                      @click="seekToEvidence(hit.startMs)"
                  >
                    <strong>{{ formatEvidenceTime(hit.startMs) }}</strong>
                    <small>{{ hit.source || '视频证据' }}</small>
                    <span>{{ hit.snippet || '该时间段暂无可展示文本' }}</span>
                  </button>
                </div>
              </div>
              <div class="markdown-content" v-html="renderedMarkdown" @click="seekEvidence"></div>
              <details v-if="sidebar.plan?.tasks?.length || traceStages.length" class="agent-inspector">
                <summary>分析详情</summary>
                <div class="agent-inspector-content">
                <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
                  <span class="meta-label">Planner 任务</span>
                  <div v-if="sidebar.editingPlan" class="plan-editor">
                    <div v-for="(_, index) in sidebar.planDraft" :key="index" class="plan-editor-row">
                      <input v-model="sidebar.planDraft[index]" maxlength="500" :aria-label="`任务 ${index + 1}`" />
                      <button type="button" title="删除任务" @click="removePlanTask(index)">×</button>
                    </div>
                    <button v-if="sidebar.planDraft.length < 5" type="button" @click="addPlanTask">添加任务</button>
                    <div class="plan-editor-actions">
                      <button type="button" @click="cancelPlanEdit">取消</button>
                      <button type="button" :disabled="sidebar.rerunLoading" @click="rerunWithPlan">
                        {{ sidebar.rerunLoading ? '提交中' : '按新计划重跑' }}
                      </button>
                    </div>
                  </div>
                  <template v-else>
                    <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
                    <button type="button" class="plan-edit-trigger" @click="startPlanEdit">调整计划</button>
                  </template>
                </div>
                <div v-if="traceStages.length" class="agent-meta-block">
                  <span class="meta-label">执行轨迹</span>
                  <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}</span></div>
                </div>
                <div v-if="sidebar.evaluation && Object.keys(sidebar.evaluation).length" class="quality-row">
                  <span>结构完整 {{ sidebar.evaluation.structuredValid ? '通过' : '待完善' }}</span>
                  <span>证据支持 {{ formatPercent(sidebar.evaluation.evidenceSupportRate) }}</span>
                  <span>Critic {{ sidebar.evaluation.criticPassed ? '通过' : '达到轮次上限' }}</span>
                </div>
                </div>
              </details>
              <div class="follow-up-box">
                <textarea
                    v-model="sidebar.followUp"
                    aria-label="基于视频继续追问"
                    maxlength="500"
                    placeholder="基于视频继续追问...（Ctrl / ⌘ + Enter 发送）"
                    @keydown.ctrl.enter.prevent="submitFollowUp"
                    @keydown.meta.enter.prevent="submitFollowUp"
                ></textarea>
                <button :disabled="sidebar.followUpLoading || !sidebar.followUp.trim()" @click="submitFollowUp">
                  {{ sidebar.followUpLoading ? '分析中' : '追问' }}
                </button>
              </div>
              <div class="feedback-row">
                <span>这个结果有帮助吗？</span>
                <button :disabled="sidebar.feedbackLoading" :class="{ active: sidebar.feedback === 1 }" :aria-pressed="sidebar.feedback === 1" @click="sendFeedback(1)" title="有帮助">赞</button>
                <button :disabled="sidebar.feedbackLoading" :class="{ active: sidebar.feedback === -1 }" :aria-pressed="sidebar.feedback === -1" @click="sendFeedback(-1)" title="需改进">踩</button>
              </div>
            </div>
            <div v-else class="text-content">
              <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
              <template v-if="sidebar.content">
                <div class="result-actions">
                  <button type="button" @click="copyResult">复制全文</button>
                  <button type="button" @click="downloadResult">导出文本</button>
                </div>
                <p class="text-meta">{{ transcriptMeta }}</p>
                <pre>{{ sidebar.content }}</pre>
              </template>
              <p v-else-if="!sidebar.error" class="text-meta">这个视频还没有可展示的转写文本。</p>
            </div>
          </div>
          </div>
        </div>
      </div>

      <div v-if="showAuthModal" class="auth-backdrop" @click.self="closeAuthModal">
        <div
            ref="authPanel"
            class="auth-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="auth-title"
            @keydown="trapAuthFocus"
        >
          <div class="auth-header">
            <h2 id="auth-title" class="auth-title">{{ authMode === 'login' ? '用户登录' : '新用户注册' }}</h2>
            <button class="close-btn" @click="closeAuthModal" aria-label="关闭登录窗口">×</button>
          </div>
          <form class="auth-body" @submit.prevent="handleAuth">
            <p class="auth-intro">{{ authMode === 'login' ? '登录 DoVideo-AI，继续管理你的视频与分析任务。' : '创建账号，建立你的视频内容工作台。' }}</p>
            <div class="input-group">
              <label for="auth-username">用户名</label>
              <input id="auth-username" v-model="authForm.username" type="text" placeholder="输入账号" autocomplete="username" autofocus />
            </div>
            <div class="input-group">
              <label for="auth-password">密码</label>
              <input id="auth-password" v-model="authForm.password" type="password" placeholder="输入密码" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" />
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label for="auth-nickname">昵称</label>
              <input id="auth-nickname" v-model="authForm.nickname" type="text" placeholder="输入展示昵称" autocomplete="nickname" />
            </div>
            <div class="auth-action">
              <button type="submit" class="cyber-btn" :disabled="authLoading">
                <span v-if="!authLoading">{{ authMode === 'login' ? '立即登录' : '提交注册' }}</span>
                <span v-else>请求处理中...</span>
              </button>
            </div>
            <div class="auth-toggle">
              <span class="toggle-text">{{ authMode === 'login' ? '没有账号?' : '已有账号?' }}</span>
              <button type="button" class="toggle-link" :disabled="authLoading" @click="switchAuthMode()">{{ authMode === 'login' ? '去注册' : '去登录' }}</button>
            </div>
            <p
                v-if="authMessage"
                class="auth-msg"
                :class="{'error': authError}"
                :role="authError ? 'alert' : 'status'"
                aria-live="polite"
            >{{ authMessage }}</p>
          </form>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch, onMounted, onUnmounted } from 'vue'
import { apiRequest, captureAuthSession, clearAuthToken, hasAuthToken, setAuthToken } from './api'
import {
  forgetUploadProgress,
  formatBytes,
  formatDurationText,
  hasUploadProgress,
  uploadVideoInChunks,
  validateVideoFile
} from './chunkUpload'
import { DEMO_ITEM } from './demoData'
import { createTaskStreams } from './taskEvents'
import { useAnalysisWorkspace } from './useAnalysisWorkspace'

// --- 变量定义 ---
const DEMO_MODE = new URLSearchParams(window.location.search).has('demo')
const MESSAGE_TIMEOUT_MS = 4000
const file = ref(null)
const videoUrl = ref('')
const message = ref('')
const messageIsError = ref(false)
const uploading = ref(false)
const uploadProgress = ref({ label: '准备上传', filename: '', percent: null, detail: '', warning: '' })
const uploadAbort = ref(null)
const resumableFile = ref(null)
const resumableChunks = ref({ done: 0, total: 0 })
const list = ref([])
const listLoading = ref(false)
const listError = ref('')
let listRequestVersion = 0
let uploadRequestVersion = 0
let authRequestVersion = 0
const searchQuery = ref('')
const videoPlayer = ref(null)
const sidebarPanel = ref(null)
const sidebarBody = ref(null)
const authPanel = ref(null)
const deletingId = ref(null)
const isOffline = ref(typeof navigator !== 'undefined' && navigator.onLine === false)
const activeTasks = ref([])
const elapsedSeconds = ref(0)
const visibleList = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  if (!query) return list.value
  return list.value.filter(item => item.filename?.toLocaleLowerCase().includes(query))
})
const isDragOver = ref(false)
const currentUser = ref(null)
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })
const taskStreams = createTaskStreams({
  onActiveChange: tasks => { activeTasks.value = tasks }
})
let dragDepth = 0
let messageTimer = null
let elapsedTimer = null
let lastUploadProgress = {}
let focusBeforeAuth = null
let focusBeforeSidebar = null

// --- 状态展示 ---
const activeTaskOf = mediaId => activeTasks.value.find(task => String(task.id) === String(mediaId))

const systemStatusText = computed(() => {
  if (isOffline.value) return '网络已断开'
  if (uploading.value) {
    return uploadProgress.value.percent !== null
      ? `上传 ${uploadProgress.value.percent}%`
      : '处理中'
  }
  if (activeTasks.value.length) return `后台任务 ${activeTasks.value.length}`
  return '工作台就绪'
})

const cardStatusClass = item => {
  if (activeTaskOf(item.id)) return 'processing'
  return mediaStatusClass(item.status)
}
const cardStatusLabel = item => {
  const task = activeTaskOf(item.id)
  if (task) return task.type === 'ai' ? 'AI 分析中' : '转写中'
  return mediaStatusLabel(item.status)
}
const cardStatusTitle = item => {
  const task = activeTaskOf(item.id)
  if (!task) return null
  return task.type === 'ai'
    ? 'AI 分析正在后台执行，完成后会提示你'
    : '文字提取正在后台执行，完成后会提示你'
}
const actionTitle = (item, label) => item.status === 'COMPLETED'
  ? null
  : `视频尚未处理完成，暂时无法${label}`

const elapsedLabel = computed(() => {
  const total = elapsedSeconds.value
  if (total < 1) return ''
  const minutes = String(Math.floor(total / 60)).padStart(2, '0')
  return `${minutes}:${String(total % 60).padStart(2, '0')}`
})

const loadingHeadline = computed(() => {
  const fallback = sidebar.value.type === 'ai'
    ? 'Agent 正在分析视频证据'
    : '正在识别视频语音'
  const headline = sidebar.value.statusMessage || fallback
  // 用“已等待”而不是“已运行”：接管历史任务时计时是从打开面板算起的。
  return elapsedLabel.value ? `${headline} · 已等待 ${elapsedLabel.value}` : headline
})

const transcriptMeta = computed(() => {
  const length = sidebar.value.content?.length || 0
  if (!length) return ''
  return `共 ${length.toLocaleString('zh-CN')} 字`
})

const resumeHint = computed(() => {
  const target = resumableFile.value
  if (!target) return ''
  const { done, total } = resumableChunks.value
  const progress = total ? `已完成 ${Math.round((done / total) * 100)}%` : '已保留上传进度'
  return `${target.name} ${progress}，可继续未完成的上传`
})

// --- 核心业务逻辑 ---

const handleDragEnter = () => {
  dragDepth += 1
  isDragOver.value = true
}

// 拖过子元素也会触发 dragleave，用进出计数避免提示文案反复闪烁。
const handleDragLeave = () => {
  dragDepth = Math.max(0, dragDepth - 1)
  if (!dragDepth) isDragOver.value = false
}

const resetDragState = () => {
  dragDepth = 0
  isDragOver.value = false
}

/** 统一入口：登录、格式、体积三道校验全部在进入上传态之前完成。 */
const startUpload = async (selectedFile, extraFileCount = 0) => {
  if (uploading.value) {
    showMsg('已有上传任务在进行，请等当前任务结束', true)
    return
  }
  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  if (!selectedFile) return
  const invalid = validateVideoFile(selectedFile)
  if (invalid) {
    showMsg(`⚠️ ${invalid}`, true)
    return
  }
  if (extraFileCount > 0) {
    showMsg(`一次只处理一个视频，已选择 ${selectedFile.name}，其余 ${extraFileCount} 个已忽略`)
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const handleFileChange = async (e) => {
  const selected = e.target.files
  await startUpload(selected?.[0], Math.max(0, (selected?.length || 0) - 1))
  e.target.value = ''
}

const handleDrop = async (e) => {
  resetDragState()
  const dropped = e.dataTransfer?.files
  if (!dropped?.length) return
  await startUpload(dropped[0], dropped.length - 1)
}

const buildUploadWarning = progress => {
  if (progress.retryingCount) {
    return `网络不稳定，正在重试 ${progress.retryingCount} 个分片（第 ${progress.retryAttempt}/${progress.retryMaxAttempts} 次）`
  }
  if (progress.resumedChunks) {
    return `已续传：跳过 ${progress.resumedChunks} 个此前完成的分片`
  }
  return ''
}

const applyUploadProgress = progress => {
  lastUploadProgress = progress
  const merging = progress.phase === 'merging'
  const detail = [`${formatBytes(progress.uploadedBytes)} / ${formatBytes(progress.totalBytes)}`]
  detail.push(`分片 ${progress.completedChunks}/${progress.totalChunks}`)
  if (!merging && progress.bytesPerSecond) {
    detail.push(`${formatBytes(progress.bytesPerSecond)}/s`)
    const eta = formatDurationText(progress.etaSeconds)
    if (eta) detail.push(`剩余约 ${eta}`)
  }
  uploadProgress.value = {
    label: merging ? '分片已全部送达，正在服务端合并' : '正在安全上传',
    filename: file.value?.name || uploadProgress.value.filename,
    percent: progress.percent,
    detail: detail.join(' · '),
    warning: buildUploadWarning(progress)
  }
}

const rememberResumableUpload = target => {
  if (!target || !hasUploadProgress(target, currentUser.value?.id)) {
    resumableFile.value = null
    return
  }
  resumableFile.value = target
  resumableChunks.value = {
    done: lastUploadProgress.completedChunks || 0,
    total: lastUploadProgress.totalChunks || 0
  }
}

const uploadFile = async () => {
  const target = file.value
  if (!target) return
  if (DEMO_MODE) {
    showMsg('演示模式：已模拟完成分片上传')
    return
  }

  const controller = new AbortController()
  const requestVersion = ++uploadRequestVersion
  const isCurrentSession = captureAuthSession()
  const isCurrentUpload = () => requestVersion === uploadRequestVersion && isCurrentSession()
  uploadAbort.value = controller
  uploading.value = true
  resumableFile.value = null
  lastUploadProgress = {}
  const uploadUserId = currentUser.value?.id
  uploadProgress.value = {
    label: hasUploadProgress(target, uploadUserId) ? '正在核对已上传分片' : '准备分片上传',
    filename: target.name,
    percent: 0,
    detail: `0 B / ${formatBytes(target.size)}`,
    warning: ''
  }

  try {
    const uploadedMedia = await uploadVideoInChunks(target, progress => {
      if (isCurrentUpload()) applyUploadProgress(progress)
    }, controller.signal, uploadUserId)
    if (!isCurrentUpload()) return
    resumableFile.value = null
    showMsg(`✅ ${target.name} 上传完成`)
    await fetchList({ notify: true })
    if (!isCurrentUpload()) return
    openAgent(uploadedMedia)
  } catch (error) {
    if (!isCurrentUpload()) return
    rememberResumableUpload(target)
    if (error?.aborted) {
      showMsg('上传已取消，进度已保留，可点“继续上传”接着传')
      return
    }
    console.error(error)
    showMsg(
      resumableFile.value
        ? `❌ 上传中断：${error.message}（进度已保留，可继续上传）`
        : `❌ 上传失败：${error.message}`,
      true
    )
  } finally {
    if (isCurrentUpload()) {
      uploading.value = false
      uploadAbort.value = null
      file.value = null
    }
  }
}

const cancelUpload = () => {
  if (!uploadAbort.value) return
  uploadProgress.value = { ...uploadProgress.value, label: '正在取消上传', warning: '' }
  uploadAbort.value.abort()
}

const resumeUpload = async () => {
  const target = resumableFile.value
  if (!target || uploading.value) return
  file.value = target
  await uploadFile()
}

const discardResumableUpload = () => {
  forgetUploadProgress(resumableFile.value, currentUser.value?.id)
  resumableFile.value = null
  resumableChunks.value = { done: 0, total: 0 }
  showMsg('已清除保留的上传进度，下次将从头开始')
}

const handleUrlUpload = async () => {
  const normalizedUrl = videoUrl.value.trim()
  if (!normalizedUrl) return
  if (uploading.value) {
    showMsg('已有上传任务在进行，请等当前任务结束', true)
    return
  }
  if (DEMO_MODE) {
    videoUrl.value = ''
    showMsg('演示模式：已模拟完成链接解析')
    return
  }

  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }

  let parsedUrl
  try {
    parsedUrl = new URL(normalizedUrl)
  } catch {
    parsedUrl = null
  }
  if (!parsedUrl || !['http:', 'https:'].includes(parsedUrl.protocol)) {
    showMsg('⚠️ 请输入合法的 http/https 链接', true)
    return
  }

  uploading.value = true
  const requestVersion = ++uploadRequestVersion
  const isCurrentSession = captureAuthSession()
  const isCurrentUpload = () => requestVersion === uploadRequestVersion && isCurrentSession()
  uploadProgress.value = {
    label: '正在解析视频链接',
    filename: parsedUrl.hostname,
    percent: null,
    detail: '服务端正在拉取源视频，时长取决于源站速度',
    warning: ''
  }
  messageIsError.value = false
  message.value = '正在解析链接并极速下载 (低码率模式)...'

  const formData = new FormData()
  formData.append('url', normalizedUrl)

  try {
    const res = await apiRequest('/media/upload-url', {
      method: 'POST',
      body: formData
    })
    if (!res.ok) throw new Error(await res.text())
    const uploadedMedia = await res.json()
    if (!isCurrentUpload()) return

    showMsg('✅ 链接资源已入库')
    videoUrl.value = ''
    await fetchList({ notify: true })
    if (!isCurrentUpload()) return
    openAgent(uploadedMedia)
  } catch (error) {
    console.error(error)
    if (!isCurrentUpload()) return
    let errMsg = error.message
    if (errMsg.includes("Unsupported URL")) errMsg = "不支持该平台链接"
    showMsg('❌ 解析失败: ' + errMsg, true)
  } finally {
    if (isCurrentUpload()) uploading.value = false
  }
}

/** 成功提示自动消失；错误提示保留到用户点掉，避免关键失败原因 4 秒后就没了。 */
const showMsg = (msg, isError = false) => {
  clearTimeout(messageTimer)
  messageTimer = null
  message.value = msg
  messageIsError.value = isError
  if (isError) return
  messageTimer = setTimeout(() => {
    if (message.value !== msg) return
    message.value = ''
    messageIsError.value = false
  }, MESSAGE_TIMEOUT_MS)
}

const dismissMessage = () => {
  if (!messageIsError.value) return
  clearTimeout(messageTimer)
  messageTimer = null
  message.value = ''
  messageIsError.value = false
}

const fetchList = async ({ notify = false } = {}) => {
  const requestVersion = ++listRequestVersion
  const isCurrentSession = captureAuthSession()
  const isCurrentRequest = () => requestVersion === listRequestVersion && isCurrentSession()
  if (DEMO_MODE) return list.value
  if (!currentUser.value) {
    list.value = []
    return list.value
  }
  listLoading.value = true
  listError.value = ''
  try {
    // 带时间戳绕开浏览器缓存，避免删除/新增之后列表还是旧的。
    const res = await apiRequest(`/media/list?_t=${Date.now()}`)
    if (res.status === 401) return null
    if (!res.ok) throw new Error('加载视频列表失败')
    const media = await res.json()
    if (!isCurrentRequest()) return null
    list.value = media
  } catch (error) {
    if (!isCurrentRequest()) return null
    console.error(error)
    listError.value = '视频资料库加载失败，请稍后重试'
    if (notify) showMsg('视频资料库加载失败，请稍后刷新', true)
    return null
  } finally {
    if (isCurrentRequest()) listLoading.value = false
  }
  return list.value
}

const mediaStatusClass = status => ['COMPLETED', 'PROCESSING', 'FAILED'].includes(status)
  ? status.toLowerCase()
  : 'unknown'
const mediaStatusLabel = status => ({
  COMPLETED: '可分析',
  PROCESSING: '处理中',
  FAILED: '处理失败'
})[status] || '待处理'

const {
  sidebar,
  goalPresets,
  analysisModes,
  traceStages,
  renderedMarkdown,
  transcribe,
  closeSidebar,
  openAgent,
  submitAgent,
  startNewAnalysis,
  showDemoResult,
  startPlanEdit,
  cancelPlanEdit,
  addPlanTask,
  removePlanTask,
  rerunWithPlan,
  submitFollowUp,
  searchEvidence,
  sendFeedback,
  retryPlayback,
  handlePlaybackError,
  resetWorkspace,
  discardMediaWorkspace,
  formatPercent
} = useAnalysisWorkspace({
  demoMode: DEMO_MODE,
  taskStreams,
  showMessage: showMsg,
  refreshMediaList: fetchList,
  findMediaItem: id => list.value.find(item => item.id === id),
  onAnswerAppended: () => scrollToLatestAnswer()
})

/** 追问的答案追加在长文末尾，主动滚过去，否则用户会以为“点了没反应”。 */
const scrollToLatestAnswer = async () => {
  await nextTick()
  const container = sidebarBody.value?.querySelector('.markdown-content')
  if (!container) return
  const headings = container.querySelectorAll('h2, h3')
  const anchor = headings.length ? headings[headings.length - 1] : container.lastElementChild
  anchor?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const seekVideo = seconds => {
  if (!Number.isFinite(seconds)) return
  const player = videoPlayer.value
  if (!player) {
    if (sidebar.value.playbackError) {
      showMsg('原视频加载失败，无法跳转，可先点“重新加载”', true)
    } else if (sidebar.value.playbackLoading) {
      showMsg('原视频还在载入，稍等一下再点这个时间戳')
    } else {
      showMsg('这个视频暂时没有可播放的原片，无法跳转', true)
    }
    return
  }
  if (player.readyState === 0) {
    player.addEventListener('loadedmetadata', () => seekVideo(seconds), { once: true })
    return
  }
  const duration = player.duration
  const maxTime = Number.isFinite(duration) ? Math.max(0, duration - 0.1) : Number.MAX_SAFE_INTEGER
  player.currentTime = Math.min(Math.max(0, seconds), maxTime)
  player.play().catch(() => {})
  player.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

const seekEvidence = event => {
  const link = event.target.closest('a[href^="#video-t="]')
  if (!link) return
  event.preventDefault()
  seekVideo(Number(link.getAttribute('href').split('=')[1]))
}

const seekToEvidence = timestampMs => seekVideo(Number(timestampMs) / 1000)
const formatEvidenceTime = timestampMs => {
  const seconds = Math.max(0, Math.floor(Number(timestampMs) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const time = `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
  return hours ? `${String(hours).padStart(2, '0')}:${time}` : time
}

/** Clipboard API 在非 HTTPS 环境不可用，这里保留一条降级路径，避免“复制失败”变成死路。 */
const copyToClipboard = async text => {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // 继续走下面的降级方案。
  }
  try {
    const scratch = document.createElement('textarea')
    scratch.value = text
    scratch.setAttribute('readonly', '')
    scratch.style.position = 'fixed'
    scratch.style.top = '0'
    scratch.style.opacity = '0'
    document.body.appendChild(scratch)
    scratch.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(scratch)
    return copied
  } catch {
    return false
  }
}

const copyResult = async () => {
  const content = sidebar.value.content
  if (!content) {
    showMsg('还没有可复制的内容', true)
    return
  }
  const label = sidebar.value.type === 'ai' ? '分析结果' : '转写全文'
  if (await copyToClipboard(content)) showMsg(`${label}已复制`)
  else showMsg('复制失败，请手动选中内容后复制', true)
}

const resultFileBaseName = () => {
  const title = sidebar.value.title || ''
  const raw = title.split(' · ').slice(1).join(' · ') || title
  const cleaned = raw.replace(/\.[^/.]+$/, '').replace(/[\\/:*?"<>|]/g, '_').trim()
  return cleaned || (sidebar.value.type === 'ai' ? 'analysis' : 'transcript')
}

const downloadResult = () => {
  const content = sidebar.value.content
  if (!content) {
    showMsg('还没有可导出的内容', true)
    return
  }
  const isMarkdown = sidebar.value.type === 'ai'
  const blob = new Blob([content], {
    type: isMarkdown ? 'text/markdown;charset=utf-8' : 'text/plain;charset=utf-8'
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${resultFileBaseName()}.${isMarkdown ? 'md' : 'txt'}`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 立刻 revoke 在部分浏览器会导致下载拿不到内容，延后一拍更稳。
  setTimeout(() => URL.revokeObjectURL(url), 0)
  showMsg(`已导出 ${link.download}`)
}

const deleteItem = async (item) => {
  if (DEMO_MODE) {
    list.value = list.value.filter(i => i.id !== item.id)
    discardMediaWorkspace(item.id)
    showMsg('演示任务已移除')
    return
  }
  if (deletingId.value) return
  const runningTask = activeTaskOf(item.id)
  const warning = runningTask
    ? '\n\n注意：该视频还有任务正在后台执行，删除后这次的结果会丢失。'
    : ''
  if (!confirm(`确认要永久删除 "${item.filename}" 吗？${warning}`)) return
  deletingId.value = item.id
  const isCurrentSession = captureAuthSession()
  try {
    const res = await apiRequest(`/media/delete?id=${item.id}`, { method: 'DELETE' })
    const text = await res.text()
    if (!isCurrentSession()) return
    if (res.ok) {
      listRequestVersion += 1
      listLoading.value = false
      showMsg(`已删除 ${item.filename}`)
      list.value = list.value.filter(i => i.id !== item.id)
      discardMediaWorkspace(item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    if (!isCurrentSession()) return
    showMsg('❌ 删除请求失败', true)
  } finally {
    if (isCurrentSession()) deletingId.value = null
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  if (Number.isNaN(date.getTime())) return '--'
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  if (DEMO_MODE) {
    showMsg(`演示模式：${item.filename} 音频已准备`)
    return
  }
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    showMsg('正在转码并下载...')
    const res = await apiRequest(`/analysis/download?id=${item.id}`)
    // 失败时后端返回的是 JSON 信封，api.js 会把 message 解包给 text()，
    // 这里读出来向上抛，避免把“视频不存在 / 无权访问 / 转码失败”统一显示成同一句话。
    if (!res.ok) throw new Error((await res.text()) || '请稍后重试')
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('✅ 下载完成')
  } catch (e) {
    showMsg('音频下载失败：' + (e?.message || '请稍后重试'), true)
  }
}

const restoreFocus = element => {
  if (element?.isConnected && typeof element.focus === 'function') element.focus()
}

const openAuthModal = () => {
  if (showAuthModal.value) return
  focusBeforeAuth = document.activeElement
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { username: '', password: '', nickname: '' }
}
const closeAuthModal = () => {
  authRequestVersion += 1
  authLoading.value = false
  showAuthModal.value = false
  restoreFocus(focusBeforeAuth)
  focusBeforeAuth = null
}
const closeActiveOverlay = () => {
  if (showAuthModal.value) closeAuthModal()
  else if (sidebar.value.visible) closeSidebar()
}
const handleKeydown = event => {
  if (event.key === 'Escape') closeActiveOverlay()
}

/** 弹窗内循环 Tab，键盘用户不会一路跳到被遮住的背景里。 */
const trapAuthFocus = event => {
  trapPanelFocus(event, authPanel.value)
}
const trapPanelFocus = (event, panel) => {
  if (event.key !== 'Tab' || !panel) return
  const focusable = [...panel.querySelectorAll('button, input, textarea, select, a[href], summary, [tabindex]:not([tabindex="-1"])')]
    .filter(element => !element.disabled && element.offsetParent !== null)
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === panel || !panel.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

const switchAuthMode = ({ keepMessage = false } = {}) => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  if (!keepMessage) authMessage.value = ''
}
const handleAuth = async () => {
  if (authLoading.value) return
  if (!authForm.value.username || !authForm.value.password) {
    authMessage.value = '请输入完整的账号和密码'
    authError.value = true
    return
  }
  authLoading.value = true
  authMessage.value = ''
  const requestVersion = ++authRequestVersion
  const mode = authMode.value
  const endpoint = mode === 'login' ? '/user/login' : '/user/register'
  try {
    const res = await apiRequest(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    if (requestVersion !== authRequestVersion) return
    if (!res.ok) {
      const message = await res.text()
      if (requestVersion !== authRequestVersion) return
      authMessage.value = message || `请求失败（HTTP ${res.status}）`
      authError.value = true
      return
    }
    const data = await res.json().catch(() => null)
    if (requestVersion !== authRequestVersion) return
    if (!data?.userInfo) {
      authMessage.value = '服务端返回异常，请稍后重试'
      authError.value = true
      return
    }
    if (mode === 'login') {
      setAuthToken(data.token)
      currentUser.value = data.userInfo
      localStorage.setItem('user', JSON.stringify(data.userInfo))
      closeAuthModal()
      showMsg(`欢迎回来，${data.userInfo.nickname}`)
      fetchList({ notify: true })
    } else {
      authMessage.value = '注册成功，账号密码已保留，直接点“立即登录”即可'
      authError.value = false
      switchAuthMode({ keepMessage: true })
    }
  } catch (e) {
    if (requestVersion !== authRequestVersion) return
    console.error(e)
    authMessage.value = e?.message || '网络连接错误'
    authError.value = true
  } finally {
    if (requestVersion === authRequestVersion) authLoading.value = false
  }
}
/** 退出与登录失效走同一套清理，避免两处漏掉不同的字段。 */
const resetSessionState = () => {
  uploadRequestVersion += 1
  listRequestVersion += 1
  authRequestVersion += 1
  authLoading.value = false
  deletingId.value = null
  listLoading.value = false
  listError.value = ''
  uploadAbort.value?.abort()
  uploadAbort.value = null
  taskStreams.stopAll()
  resetWorkspace()
  currentUser.value = null
  list.value = []
  searchQuery.value = ''
  videoUrl.value = ''
  file.value = null
  resumableFile.value = null
  resumableChunks.value = { done: 0, total: 0 }
  uploading.value = false
  localStorage.removeItem('user')
}

const logout = () => {
  if (hasAuthToken()) {
    apiRequest('/user/logout', { method: 'POST' }).catch(() => {})
  }
  resetSessionState()
  clearAuthToken()
  showMsg('已退出系统')
}

const handleAuthExpired = () => {
  resetSessionState()
  showMsg('登录状态已失效，请重新登录', true)
  openAuthModal()
}

const handleOnline = () => {
  isOffline.value = false
  showMsg('网络已恢复，正在同步最新状态')
  if (currentUser.value) fetchList()
}

const handleOffline = () => {
  isOffline.value = true
  showMsg('网络已断开：上传会自动重试，后台任务会在恢复后继续', true)
}

// 上传中误关标签页会白丢已传分片，这里让浏览器先问一句。
const handleBeforeUnload = event => {
  if (!uploading.value) return
  event.preventDefault()
  event.returnValue = ''
}

// 打开弹层时挂上标记类，锁背景滚动的规则只在窄屏生效（见样式里的说明）。
const overlayOpen = computed(() => sidebar.value.visible || showAuthModal.value)
watch(overlayOpen, open => {
  document.body.classList.toggle('overlay-open', open)
})

watch(() => sidebar.value.visible, async visible => {
  if (visible) {
    focusBeforeSidebar = document.activeElement
    await nextTick()
    sidebarPanel.value?.focus()
    return
  }
  restoreFocus(focusBeforeSidebar)
  focusBeforeSidebar = null
})

// 长任务给一个时间锚点，用户才不会怀疑是不是卡死了。
watch(() => sidebar.value.loading, loading => {
  clearInterval(elapsedTimer)
  elapsedTimer = null
  elapsedSeconds.value = 0
  if (!loading) return
  const startedAt = Date.now()
  elapsedTimer = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 1000)
})

onMounted(() => {
  window.addEventListener('auth-expired', handleAuthExpired)
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('online', handleOnline)
  window.addEventListener('offline', handleOffline)
  window.addEventListener('beforeunload', handleBeforeUnload)
  if (DEMO_MODE) {
    currentUser.value = { id: 1, nickname: 'Agent Demo' }
    list.value = [DEMO_ITEM]
    openAgent(DEMO_ITEM)
    showDemoResult()
    return
  }
  const savedUser = localStorage.getItem('user')
  if (savedUser && hasAuthToken()) {
    try {
      currentUser.value = JSON.parse(savedUser)
    } catch(e) {}
  }
  fetchList({ notify: Boolean(currentUser.value) })
})
onUnmounted(() => {
  uploadRequestVersion += 1
  listRequestVersion += 1
  authRequestVersion += 1
  resetWorkspace()
  window.removeEventListener('auth-expired', handleAuthExpired)
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('online', handleOnline)
  window.removeEventListener('offline', handleOffline)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  clearTimeout(messageTimer)
  clearInterval(elapsedTimer)
  uploadAbort.value?.abort()
  document.body.classList.remove('overlay-open')
  taskStreams.stopAll()
})
</script>

<style src="./workspace.css"></style>
