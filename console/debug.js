import NostrAds from './nostrads-client.js';

NostrAds({
    devMode: true,
    appKey: "npub13rugy09zg5pssxtjfvgkhjjzkx8swpvwr7e2gymnr2jp2ltveeqs88pjk4",
    allowedImageOrigins: ["https://blossom.primal.net", "https://blossom.band"]
});

const slots = [
    { w: 480, h: 60, title: "Banner (8:1) 480x60" },
    { w: 720, h: 90, title: "Leaderboard (8:1) 720x90" },
    { w: 512, h: 128, title: "Horizontal Banner (4:1) 512x128" },
    { w: 512, h: 256, title: "Horizontal Narrow Banner (2:1) 512x256" },
    { w: 256, h: 512, title: "Vertical Banner (1:2) 256x512" },
    { w: 128, h: 512, title: "Vertical Narrow Banner (1:4) 128x512" },
    { w: 256, h: 256, title: "Square (1:1) 256x256" }
];

function addSlot(slot) {
    const space = document.createElement('div');
    space.className = 'nostr-ddspace';
    if (slot) {
        space.style.width = `${slot.w}px`;
        space.style.height = `${slot.h}px`;
        space.title = slot.title;
    }
    document.body.appendChild(space);
}

document.getElementById('add-invalid-space').addEventListener('click', () => addSlot(null));
const container = document.getElementById('slot-buttons');
for (const slot of slots) {
    const button = document.createElement('button');
    button.textContent = `Add ${slot.title}`;
    button.addEventListener('click', () => addSlot(slot));
    container.appendChild(button);
}
