

async function load(view) {

    const h1=view.querySelector('h1');
    if (h1) {
        h1.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            window.location.href="/console";
        };
        h1.style.cursor = 'pointer';
    }

  


}

export default load;