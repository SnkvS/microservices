const assert = require('node:assert/strict');
const fs = require('node:fs');
const {setTimeout: delay} = require('node:timers/promises');

const [songUrl, resourceUrl, fixture] = process.argv.slice(2);
if (!songUrl || !resourceUrl || !fixture) {
    console.error('Usage: node api-tests/verify-module-2.cjs SONG_URL RESOURCE_URL VALID_MP3_PATH');
    process.exit(1);
}

const expected = {name: 'Test Title', artist: 'Test Artist', album: 'Test Album', duration: '00:07', year: '2025'};
const outstanding = new Set();
let requests = 0;

async function request(url, status, options) {
    const response = await fetch(url, {...options, signal: AbortSignal.timeout(30000)});
    requests++;
    assert.equal(response.status, status, `${options?.method || 'GET'} ${url}: ${await response.clone().text()}`);
    return response;
}

async function waitForApis() {
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
        try {
            const statuses = await Promise.all([
                `${resourceUrl}/resources/999999999999999999`, `${songUrl}/songs/999999999999999999`
            ].map(async url => {
                const response = await fetch(url, {signal: AbortSignal.timeout(3000)});
                requests++;
                await response.text();
                return response.status;
            }));
            if (statuses.every(status => status === 404)) return;
        } catch { /* Discovery and HTTP services may still be starting. */ }
        await delay(500);
    }
    throw new Error('Resource and Song APIs did not become available within 60 seconds');
}

(async () => {
    try {
        const bytes = fs.readFileSync(fixture);
        await waitForApis();
        const uploaded = await request(`${resourceUrl}/resources`, 200, {
            method: 'POST', headers: {'Content-Type': 'audio/mpeg'}, body: bytes
        });
        const {id} = await uploaded.json();
        assert.ok(Number.isSafeInteger(id) && id > 0);
        outstanding.add(id);
        const deadline = Date.now() + 60000;
        let song;
        while (Date.now() < deadline) {
            const response = await fetch(`${songUrl}/songs/${id}`, {signal: AbortSignal.timeout(5000)});
            requests++;
            if (response.status === 200) {
                song = await response.json();
                break;
            }
            assert.equal(response.status, 404, `Metadata poll failed: ${await response.text()}`);
            await delay(500);
        }
        assert.deepEqual(song, {id, ...expected}, 'Extracted metadata must appear within 60 seconds');
        const downloaded = await request(`${resourceUrl}/resources/${id}`, 200);
        assert.equal(downloaded.headers.get('content-type'), 'audio/mpeg');
        assert.equal(Number(downloaded.headers.get('content-length')), bytes.length);
        assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), bytes);
        const deleted = await request(`${resourceUrl}/resources?id=${id},${id}`, 200, {method: 'DELETE'});
        assert.deepEqual(await deleted.json(), {ids: [id]});
        outstanding.delete(id);
        await request(`${songUrl}/songs/${id}`, 404);
        await request(`${resourceUrl}/resources/${id}`, 404);
        const repeated = await request(`${resourceUrl}/resources?id=${id}`, 200, {method: 'DELETE'});
        assert.deepEqual(await repeated.json(), {ids: []});
        console.log(`Module 2 passed (${requests} requests): asynchronous metadata, exact MP3 bytes, synchronous deletion.`);
    } finally {
        for (const id of outstanding) {
            try {
                await request(`${resourceUrl}/resources?id=${id}`, 200, {method: 'DELETE'});
            } catch (error) {
                console.error(`Cleanup failed for resource ${id}: ${error.message}`);
            }
        }
    }
})().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
});
