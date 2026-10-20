import { apiRequest, captureAuthSession } from './api.js'

const root = '/media/editor'
async function request(path, data, type = 'json') {
  const response = await apiRequest(path, data === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)
  })
  if (!response.ok) {
    const error = new Error(await response.text() || '请求失败，请稍后重试')
    error.status = response.status
    throw error
  }
  return type === 'blob' ? response.blob() : response.json()
}

export const editorApi = {
  captureSession: captureAuthSession,
  playback: id => request(`/media/playback?id=${encodeURIComponent(id)}`),
  project: mediaId => request(`${root}/projects?mediaId=${encodeURIComponent(mediaId)}`),
  transcript: (mediaId, transcriptId) => request(`${root}/transcripts?mediaId=${encodeURIComponent(mediaId)}${transcriptId == null ? '' : `&transcriptId=${encodeURIComponent(transcriptId)}`}`),
  importTranscript: (mediaId, format, content) => request(`${root}/transcripts/import`, { mediaId, format: format.toLowerCase(), content }),
  transcribe: mediaId => request(`${root}/transcripts/transcribe`, { mediaId }),
  transcriptStatus: mediaId => request(`${root}/transcripts/status?mediaId=${encodeURIComponent(mediaId)}`),
  createSelection: data => request(`${root}/selections`, data),
  selectionStatus: id => request(`${root}/selections/${encodeURIComponent(id)}`),
  latestSelection: (mediaId, transcriptId) => request(`${root}/selections?mediaId=${encodeURIComponent(mediaId)}&transcriptId=${encodeURIComponent(transcriptId)}`),
  saveProject: draft => request(`${root}/projects`, draft),
  frame: (mediaId, atMs) => request(`${root}/frame?mediaId=${encodeURIComponent(mediaId)}&atMs=${atMs}`, undefined, 'blob'),
  createExport: data => request(`${root}/exports`, data),
  latestExport: projectId => request(`${root}/exports?projectId=${encodeURIComponent(projectId)}`),
  exportStatus: id => request(`${root}/exports/${encodeURIComponent(id)}`),
  download: id => request(`${root}/exports/${encodeURIComponent(id)}/download`)
}
