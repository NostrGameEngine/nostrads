import { localStorageKey } from './options.js';

export async function saveAd(event) {
    try {
        const key = await localStorageKey('events');
        const events = JSON.parse(localStorage.getItem(key) || '[]');
        const previous = events.findIndex((saved) => saved.id === event.id);
        if (previous >= 0) events[previous] = event;
        else events.push(event);
        localStorage.setItem(key, JSON.stringify(events));
    } catch (error) {
        console.error('Error saving ad:', error);
    }
}

export async function getAds() {
    try {
        const key = await localStorageKey('events');
        const events = JSON.parse(localStorage.getItem(key) || '[]');
        return events;
    } catch (error) {
        console.error('Error loading ads:', error);
        return [];
    }
}

export async function deleteAd(eventId) {
    try {
        const key = await localStorageKey('events');
        const events = JSON.parse(localStorage.getItem(key) || '[]');
        const updatedEvents = events.filter((event) => event.id !== eventId);
        localStorage.setItem(key, JSON.stringify(updatedEvents));
    } catch (error) {
        console.error('Error deleting ad:', error);
    }
}
