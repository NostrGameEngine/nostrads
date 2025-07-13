export function saveAd(event){
    try {
        const events = JSON.parse(localStorage.getItem('nostrads-events')||"[]");
        events.push(event);
        localStorage.setItem('nostrads-events', JSON.stringify(events));
        console.log("Ad saved successfully:", event);
    } catch (error) {
        console.error("Error saving ad:", error);
    }
}

export function getAds(){
    try {
        const events = JSON.parse(localStorage.getItem('nostrads-events')||"[]");
        console.log("Loaded ads:", events);
        return events;
    } catch (error) {
        console.error("Error loading ads:", error);
        return [];
    }
}

export function deleteAd(eventId) {
    try {
        const events = JSON.parse(localStorage.getItem('nostrads-events')||"[]");
        const updatedEvents = events.filter(event => event.id !== eventId);
        localStorage.setItem('nostrads-events', JSON.stringify(updatedEvents));
        console.log("Ad deleted successfully:", eventId);
    } catch (error) {
        console.error("Error deleting ad:", error);
    }
}   