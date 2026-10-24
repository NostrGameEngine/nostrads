const target = new URL('../', import.meta.url);
target.hash = location.search ? `edit?${location.search.slice(1)}` : 'new';
location.replace(target);
