import test from 'node:test'
import assert from 'node:assert/strict'
import { editorApi } from './editorApi.js'

test('subtitle import sends the lowercase format accepted by the real controller', async () => {
  globalThis.localStorage = { getItem: () => null }
  for (const format of ['SRT', 'VTT']) {
    let sent
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/media/editor/transcripts/import')
      sent = JSON.parse(options.body)
      return new Response(JSON.stringify({ code: 0, message: 'success', data: { id: 1 } }), {
        headers: { 'Content-Type': 'application/json' }
      })
    }
    assert.deepEqual(await editorApi.importTranscript(3, format, 'subtitle content'), { id: 1 })
    assert.deepEqual(sent, { mediaId: 3, format: format.toLowerCase(), content: 'subtitle content' })
  }
})

test('smart selections use scoped async job endpoints and unwrap the standard envelope', async () => {
  globalThis.localStorage = { getItem: () => null }
  const responses = [{ id: 'job /1', state: 'QUEUED' }, { id: 'job /1', state: 'COMPLETED' }, null]
  const sent = []
  globalThis.fetch = async (url, options) => {
    sent.push({ url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null })
    return new Response(JSON.stringify({ code: 0, message: 'success', data: responses.shift() }), { headers: { 'Content-Type': 'application/json' } })
  }
  assert.deepEqual(await editorApi.createSelection({ mediaId: 3, transcriptId: 7, goal: '问答' }), { id: 'job /1', state: 'QUEUED' })
  assert.deepEqual(await editorApi.selectionStatus('job /1'), { id: 'job /1', state: 'COMPLETED' })
  assert.equal(await editorApi.latestSelection(3, 7), null)
  assert.deepEqual(sent, [
    { url: '/media/editor/selections', method: 'POST', body: { mediaId: 3, transcriptId: 7, goal: '问答' } },
    { url: '/media/editor/selections/job%20%2F1', method: 'GET', body: null },
    { url: '/media/editor/selections?mediaId=3&transcriptId=7', method: 'GET', body: null }
  ])
})
