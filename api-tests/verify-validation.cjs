const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const [songUrl, resourceUrl, validMp3] = process.argv.slice(2);
if (!songUrl || !resourceUrl || !validMp3) {
    console.error('Usage: node api-tests/verify-validation.cjs SONG_URL RESOURCE_URL VALID_MP3_PATH');
    process.exit(1);
}
const base = {name: 'Validation check', artist: 'Test', album: 'Test', duration: '00:07', year: '2025'};
const firstId = Date.now();
const cases = [
    ['missing id', {id: undefined}, 400],
    ['zero id', {id: 0}, 400],
    ['negative id', {id: -1}, 400],
    ['decimal id', {id: firstId + 0.5}, 400],
    ['string id', {id: String(firstId)}, 400],
    ['numeric name', {name: 12345}, 400],
    ['numeric year', {year: 2025}, 400],
    ['null name', {name: null}, 400],
    ['name 100 characters', {name: 'a'.repeat(100)}, 200],
    ['name 101 characters', {name: 'a'.repeat(101)}, 400],
    ['artist 101 characters', {artist: 'a'.repeat(101)}, 400],
    ['album 101 characters', {album: 'a'.repeat(101)}, 400],
    ['year 1900', {year: '1900'}, 200],
    ['year 2099', {year: '2099'}, 200],
    ['year 1899', {year: '1899'}, 400],
    ['year 2100', {year: '2100'}, 400],
    ['duration 00:00', {duration: '00:00'}, 200],
    ['duration 99:59', {duration: '99:59'}, 200],
    ['duration 00:60', {duration: '00:60'}, 400],
    ['duration 1:00', {duration: '1:00'}, 400]
];

(async () => {
    const created = [];
    try {
        for (let i = 0; i < cases.length; i++) {
            const [name, change, expected] = cases[i];
            const request = {...base, id: firstId + i + 1, ...change};
            const response = await fetch(`${songUrl}/songs`, {
                method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(request)
            });
            const body = await response.json();
            if (response.status === 200) created.push(body.id);
            assert.equal(response.status, expected, `${name}: ${JSON.stringify(body)}`);
            if (expected === 400) {
                assert.equal(body.errorCode, '400', name);
                assert.equal(body.errorMessage, 'Validation error', name);
                assert.ok(body.details && Object.keys(body.details).length > 0, `${name}: field details missing`);
            }
            console.log(`PASS ${name}`);
        }
        const invalidBodies = [
            ['invalid MP3 bytes', Buffer.from('not an MP3')],
            ['empty MP3', Buffer.alloc(0)]
        ];
        for (const [name, body] of invalidBodies) {
            const response = await fetch(`${resourceUrl}/resources`, {
                method: 'POST',
                headers: {'Content-Type': 'audio/mpeg'},
                body
            });
            assert.equal(response.status, 400, name);
            assert.equal((await response.json()).errorCode, '400', name);
            console.log(`PASS ${name}`);
        }
        const missingTagsPath = path.join(path.dirname(validMp3), path.basename(validMp3) === 'valid.mp3'
            ? 'missing-tags.mp3' : 'invalid-sample-with-missed-tags.mp3');
        const uploaded = await fetch(`${resourceUrl}/resources`, {
            method: 'POST', headers: {'Content-Type': 'audio/mpeg'}, body: fs.readFileSync(missingTagsPath)
        });
        assert.equal(uploaded.status, 200, 'Valid MP3 without song tags should be stored');
        const resourceId = (await uploaded.json()).id;
        assert.ok(Number.isSafeInteger(resourceId) && resourceId > 0, 'Upload must return a positive ID');
        try {
            const metadata = await fetch(`${songUrl}/songs/${resourceId}`);
            assert.equal(metadata.status, 404, 'Upload must not create song metadata');
        } finally {
            const removed = await fetch(`${resourceUrl}/resources?id=${resourceId}`, {method: 'DELETE'});
            assert.equal(removed.status, 200, 'Missing-tag MP3 cleanup failed');
        }
        console.log(`All ${cases.length + invalidBodies.length + 1} validation scenarios passed.`);
    } finally {
        if (created.length) {
            const response = await fetch(`${songUrl}/songs?id=${created.join(',')}`, {method: 'DELETE'});
            assert.equal(response.status, 200, 'Test metadata cleanup failed');
        }
    }
})().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
});
