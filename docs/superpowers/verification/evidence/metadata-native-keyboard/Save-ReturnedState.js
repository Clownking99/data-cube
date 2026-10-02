// node_repl recipe used for evidence archival only. No Windows input or screenshot recapture.
// Initialize sky and select a returned target Window separately per the Computer Use skill.
// globalThis.state is the actual result of the immediately preceding sky.get_window_state.
globalThis.fs = await import('node:fs/promises');
globalThis.evidence = 'C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾/docs/superpowers/verification/evidence/metadata-native-keyboard';
globalThis.seq = 0;
globalThis.saveState = async (label) => {
    for (let i = 0; i < state.screenshots.length; i++) {
        let shot = state.screenshots[i];
        let match = shot.url.match(/^data:image\/(\w+);base64,(.*)$/s);
        if (match) {
            await fs.writeFile(evidence + '/' + String(seq).padStart(3, '0') + '-' + label
                + '-' + i + '.' + (match[1] === 'jpeg' ? 'jpg' : match[1]),
                Buffer.from(match[2], 'base64'));
        }
    }
    await fs.appendFile(evidence + '/native-actions.jsonl', JSON.stringify({
        seq: seq++, label, time: new Date().toISOString(), window: state.window,
        accessibility: state.accessibility,
        screenshots: state.screenshots.map(({url, ...rest}) => rest)
    }) + '\n');
};
