NostrAds({
    devMode: true,
    appKey: "npub13rugy09zg5pssxtjfvgkhjjzkx8swpvwr7e2gymnr2jp2ltveeqs88pjk4",
    allowedImageOrigins: ["https://blossom.primal.net", "https://blossom.band"]
});

window.addEventListener('load', async () => {
    const code = document.getElementById('code');
    try {
        code.textContent = await fetch('./snippet.txt').then(response => response.text());
    } catch (error) {
        code.textContent = `Unable to load snippet: ${error}`;
    }
});
