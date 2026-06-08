
export default function showError(view, error) {
    const errorEl = document.querySelector('#error');
    if (!errorEl) {
        console.error("No error element found");
        return;
    }
    if(!error||error==""){
        errorEl.style.display = 'none';
     
    } else{
        errorEl.textContent = error.message || error.toString();
        errorEl.style.display = 'block';
        errorEl.scrollIntoView({ behavior: 'smooth', block: 'center' }); 
        console.error("Error:", error);
    }
    
}