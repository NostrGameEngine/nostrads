import {localStorageKey} from './options.js';


export async function saveAd(event){
    try {
        const events = JSON.parse(localStorage.getItem(await localStorageKey("events"))||"[]");
        events.push(event);
        localStorage.setItem(await localStorageKey("events"), JSON.stringify(events));
    } catch (error) {
        console.error("Error saving ad:", error);
    }
}

export async function getAds(){
    try {
        const events = JSON.parse(localStorage.getItem(await localStorageKey("events"))||"[]");
        return events;
    } catch (error) {
        console.error("Error loading ads:", error);
        return [];
    }
}

export async function deleteAd(eventId) {
    try {
        const events = JSON.parse(localStorage.getItem(await localStorageKey("events"))||"[]");
        const updatedEvents = events.filter(event => event.id !== eventId);
        localStorage.setItem(await localStorageKey("events"), JSON.stringify(updatedEvents));
    } catch (error) {
        console.error("Error deleting ad:", error);
    }
}   