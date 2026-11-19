import { describe, expect, it } from 'vitest'
import { chunkBounds, fingerprint, missingChunks, resumeKey } from './resume'

describe('upload resumption', () => {
  it('selects only missing indexes in order', () => {
    expect(missingChunks(4, [2, 0, 2])).toEqual([1, 3])
    expect(missingChunks(2, [1, 0])).toEqual([])
  })
  it('uses the true byte length of the final chunk', () => {
    expect(chunkBounds(2, 10, 4)).toEqual([8, 10])
  })
  it('does not reuse a cached session across accounts or filenames', () => {
    expect(resumeKey('alice', { name: 'x.txt', size: 3 }, 'hash'))
      .not.toEqual(resumeKey('bob', { name: 'x.txt', size: 3 }, 'hash'))
    expect(resumeKey('alice', { name: 'x.txt', size: 3 }, 'hash'))
      .not.toEqual(resumeKey('alice', { name: 'y.txt', size: 3 }, 'hash'))
  })
  it('hashes file bytes with SHA-256', async () => {
    await expect(fingerprint(new Blob(['abc']))).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
})
