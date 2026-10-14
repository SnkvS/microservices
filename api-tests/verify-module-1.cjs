const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const [songUrl, resourceUrl, validMp3] = process.argv.slice(2);
if (!songUrl || !resourceUrl || !validMp3) {
    console.error('Usage: node api-tests/verify-module-1.cjs SONG_URL RESOURCE_URL VALID_MP3_PATH');
    process.exit(1);
}

const metadata = {name: 'Module 1 test', artist: 'Test Artist', album: 'Test Album', duration: '00:07', year: '2025'};
const resources = new Set();
const songs = new Set();
const standaloneId = Date.now();
let requests = 0;

async function request(url, expected, options) {
    const response = await fetch(url, {...options, signal: AbortSignal.timeout(15000)});
    assert.equal(response.status, expected, `${options?.method || 'GET'} ${url}: ${await response.clone().text()}`);
    requests++;
    return response;
}

async function upload(bytes) {
    const response = await request(`${resourceUrl}/resources`, 200, {
        method: 'POST', headers: {'Content-Type': 'audio/mpeg'}, body: bytes
    });
    const {id} = await response.json();
    assert.ok(Number.isSafeInteger(id) && id > 0, 'Positive resource ID required');
    resources.add(id);
    return id;
}

async function createSong(id) {
    await request(`${songUrl}/songs`, 200, {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({id, ...metadata})
    });
    songs.add(id);
}

(async () => {
    let failure;
    try {
        await createSong(standaloneId);
        const bytes = fs.readFileSync(validMp3);
        const first = await upload(bytes);
        await request(`${songUrl}/songs/${first}`, 404);
        const downloaded = await request(`${resourceUrl}/resources/${first}`, 200);
        assert.equal(downloaded.headers.get('content-type'), 'audio/mpeg');
        assert.equal(Number(downloaded.headers.get('content-length')), bytes.length);
        assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), bytes, 'Original MP3 bytes must be preserved');

        await createSong(first);
        const song = await request(`${songUrl}/songs/${first}`, 200);
        assert.deepEqual(await song.json(), {id: first, ...metadata});
        const removed = await request(`${resourceUrl}/resources?id=${first},${first},${standaloneId}`, 200, {method: 'DELETE'});
        assert.deepEqual(await removed.json(), {ids: [first]});
        resources.delete(first);
        songs.delete(first);
        await request(`${resourceUrl}/resources/${first}`, 404);
        await request(`${songUrl}/songs/${first}`, 404);
        await request(`${songUrl}/songs/${standaloneId}`, 200);

        const missingTagsPath = path.join(path.dirname(validMp3), path.basename(validMp3) === 'valid.mp3'
            ? 'missing-tags.mp3' : 'invalid-sample-with-missed-tags.mp3');
        const second = await upload(fs.readFileSync(missingTagsPath));
        await request(`${songUrl}/songs/${second}`, 404);
        const untaggedRemoved = await request(`${resourceUrl}/resources?id=${second}`, 200, {method: 'DELETE'});
        assert.deepEqual(await untaggedRemoved.json(), {ids: [second]});
        resources.delete(second);
        await request(`${resourceUrl}/resources/${second}`, 404);
        const repeated = await request(`${resourceUrl}/resources?id=${second},${standaloneId}`, 200, {method: 'DELETE'});
        assert.deepEqual(await repeated.json(), {ids: []});
        await request(`${songUrl}/songs/${standaloneId}`, 200);
        console.log(`Module 1 flow passed (${requests} requests), including byte equality and standalone metadata preservation.`);
    } catch (error) {
        failure = error;
    } finally {
        for (const id of resources) {
            try {
                await request(`${resourceUrl}/resources?id=${id}`, 200, {method: 'DELETE'});
            } catch (error) {
                failure ||= error;
                console.error(`Resource cleanup failed for ${id}: ${error.message}`);
            }
        }
        for (const id of songs) {
            try {
                await request(`${songUrl}/songs?id=${id}`, 200, {method: 'DELETE'});
            } catch (error) {
                failure ||= error;
                console.error(`Song cleanup failed for ${id}: ${error.message}`);
            }
        }
    }
    if (failure) throw failure;
})().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
});
