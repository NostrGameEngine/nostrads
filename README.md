# Nostr Ads Network

Decentralized advertising on top of nostr. 
[draft nip-ADS](https://ngengine.org/docs/nip-drafts/nip-ADS/).




## Javascript Client Library

This is the client library that you can use to connect a webapp to the network.
It uses a subset of the protocol but should be sufficient for the most common use cases.

### Building

```bash
# Install dependencies
npm install webpack webpack-cli buffer  path-browserify buffer  crypto-browserify  babel-loader @babel/core @babel/preset-env  @noble/ciphers @noble/curves @noble/hashes @scure/base

# Compile the js library
./gradlew --no-daemon :nostrads-js:packJsClient

```

you should see two new files being created in `nostrads-js/nostrads-client.js` and `nostrads-js/nostrads-worker.js`.
These together with the optional css `nostrads-js/nostr-ads.css`, are all you need to include in your webapp.

### Initialization

Add this to your pages, make sure to replace `<your_path>` with the path to the files you just built.


```html
<script src="<your_path>/nostrads-client.js"></script>
<script>
    NostrAds({
        appKey: "<npub>", 
        // relays = [...],
        worker: "<your_path>/nostrads-worker.js", 
        // auth: "<npriv>", 
        // priceSlot: "BTC1_000",
        // category: [],
        // languages: [],
        // advertisersWhitelist: []
        // mimeTypes: []
    });
</script>
```

In this snippet:

##### `appKey: "<npub>"` 
the npub you want to receive payouts for interacting with the ads.
This could be a npub you set up for your app, or even the npub of the user if you want the payout to be routed directly to them (eg. as a reward system).
In either case, the npub should have an associated kind 0 event with a lud16 or lud06 address to send the payouts to.


##### `relays: [...]`

a list of relays to use, leave unset for the default list

##### `worker: "<your_path>/nostrads-worker.js"`
is the path to the worker script that you just built.

##### `auth: "<npriv>"`

is a private key that will be used to derivate the public key that identify the user in the advertising network. This identity should not be connected in anyway to the real identity (eg. social identity) of the user, and should be rotated often. The purpose of this identity is to allow and advertiser to reach out again to a specific user, eg. to promote a new product they might be interested in, but it should not be used in a way that makes long-term profiling of the user possible. 
By default leaving this unset will generate a new random private key for the user session, this is generally sufficient.

##### `priceSlot: "BTC..."`

the minimum price that the ad must pay to be displayed in the ad spaces. Must be chosen from this list: [Price Slot](https://ngengine.org/docs/nip-drafts/nip-ADS/#ad-id).
This filters out low-paying ads. By default it is set to `BTC1_000`.

##### `category: [...]`
a list of category ids to allow in the ad space. Must be chosen from this list: [Nostr Ads Taxonomy](https://ngengine.org/docs/nip-drafts/nostr-content-taxonomy/) *note: ids are set as strings*. Leave unset to allow all categories.


##### `languages: [...]`
a list of language codes to allow in the ad space (eg ["en","it"]). Must be chosen from this list: [ISO 639-1](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes). Leave unset to allow all languages.


##### `advertisersWhitelist: [...]`
list of npubs of advertisers that are allowed to display ads, this can be used to effectively implement adblocking logic. Leave unset to allow all advertisers.


##### `mimeTypes: [...]`
a list of mime types to allow in the ad space,   Must be chosen from this list: [Mime Types](https://ngengine.org/docs/nip-drafts/nip-ADS/#mime-type). Leave unset to allow all mime types.


> [!TIP]
> The library runs automatically in a shared web worker, so it will share the same session across all tabs of the same origin.


### Usage

Once you have the library set up, it will automatically start looking for ad space to fill in the page.
To define an ad space you can use this html snippet:

```html
<div class="nostr-ddspace" style="width: 250px; height: 250px;"></div>
```

> [!TIP]
> `class="nostr-ddspace"` is very important, since it is used by the library to identify the ad space.
>  Note: `ddspace` is not a typo



`width: 250px; height: 250px;` are valid dimensions for the ad chosen from this list: [Ad size and Aspect ratio](https://ngengine.org/docs/nip-drafts/nip-ADS/#ad-size-and-aspect-ratio).


> [!TIP]
> It is not mandatory to set the dimension in the style like this, the library will use whatever actual display size the element has to find the best ad to fill it, so as long as the space ends up with a size that resembles one of the valid ad sizes, it should work. However, specifying the dimensions manually makes things easier and more predictable.

#### Configuring the ad space

You can configure the ad space with attributes on the element:
- appKey
- priceSlot
- mimeTypes
- category
- languages
- advertisersWhitelist

They are the same attributes set in the [Initialization](#initialization) phase and they will override the values for a specific ad space. The only difference is that arrays are set as comma-separated strings, eg. `languages="en,it"`.


> [!TIP]
> You can add adspaces dynamically to the page, the library will automatically detect them and fill them with ads.



## Delegate Service

If you are an advertiser that wants to self-host, or you want to offer a service to advertisers, you can run a delegate service

```bash
docker run -d --name nostrads-delegate \
    -v/srv/nostrads-delegate:/data \
    ghcr.io/nostrgameengine/nostrads/nostrads-delegate:latest
```

this will automatically generate a new pubkey that you can see with

```bash
docker logs nostrads-delegate
```

>[!TIP]
> In the snipped above, `/srv/nostrads-delegate` is the path where all persistent data will be stored, including the private key of the delegate service. Change it to a path that suits your needs.


### Configuration
The service will start with sane defaults, and no fees.
You can configure it by appending arguments to the command line, for example, this will list the available options:

```bash
 docker run --name nostrads-delegate \
    ghcr.io/nostrgameengine/nostrads/nostrads-delegate:latest --help
```

To collect a fee for each successful negotiation, we can use:

```
docker run -d --name nostrads-delegate \
    -v/srv/nostrads-delegate:/data \
    ghcr.io/nostrgameengine/nostrads/nostrads-delegate:latest \
    --fee 1000:2:21000:zap@rblb.it
```

this will collect a fee of 2% with a minimum of 1 sats and a maximum of 21 sats per negotiation, and will send the fee to `zap@rblb.it`.




