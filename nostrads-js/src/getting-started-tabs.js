const tabs = [...document.querySelectorAll('.setup-tabs [role="tab"]')];
function select(tab) {
    for (const item of tabs) {
        const active = item === tab;
        item.setAttribute('aria-selected', String(active));
        item.tabIndex = active ? 0 : -1;
        document.getElementById(item.getAttribute('aria-controls')).hidden = !active;
    }
}
tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => select(tab));
    tab.addEventListener('keydown', event => {
        const offset = {ArrowRight: 1, ArrowLeft: -1}[event.key];
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
            : offset ? (index + offset + tabs.length) % tabs.length : null;
        if (next === null) return;
        event.preventDefault();
        select(tabs[next]);
        tabs[next].focus();
    });
});
