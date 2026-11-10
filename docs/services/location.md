# Amazon Location Service

**Protocol:** REST JSON, signed with `geo` (and `geo-maps`, `geo-places`, `geo-routes` for the Maps, Places and Routes APIs)
**Endpoint:** `http://localhost:4566` — `/maps/v0`, `/places/v0`, `/routes/v0`, `/geofencing/v0`, `/tracking/v0`, `/metadata/v0` and `/v2/...`

Floci emulates Amazon Location Service without a map provider. Places come from a built-in gazetteer of world cities and landmarks. Routes follow the great circle with a road detour. Map assets are drawn locally. Geofences, trackers and their events behave as on AWS.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateMap` | Create a map |
| `DescribeMap` | Describe a map |
| `UpdateMap` | Update a map |
| `DeleteMap` | Delete a map |
| `ListMaps` | Page through the maps |
| `GetMapStyleDescriptor` | Return the map's MapLibre style descriptor |
| `GetMapGlyphs` | Return a glyph range PBF |
| `GetMapSprites` | Return a sprite index or sheet |
| `GetMapTile` | Return a vector or raster tile |
| `CreatePlaceIndex` | Create a place index |
| `DescribePlaceIndex` | Describe a place index |
| `UpdatePlaceIndex` | Update a place index |
| `DeletePlaceIndex` | Delete a place index |
| `ListPlaceIndexes` | Page through the place indexes |
| `SearchPlaceIndexForText` | Search the gazetteer by text |
| `SearchPlaceIndexForPosition` | Find the places at a position |
| `SearchPlaceIndexForSuggestions` | Suggest places for partial text |
| `GetPlace` | Return a place by PlaceId (Location and Geo Places) |
| `CreateRouteCalculator` | Create a route calculator |
| `DescribeRouteCalculator` | Describe a route calculator |
| `UpdateRouteCalculator` | Update a route calculator |
| `DeleteRouteCalculator` | Delete a route calculator |
| `ListRouteCalculators` | Page through the route calculators |
| `CalculateRoute` | Calculate a route with legs and steps |
| `CalculateRouteMatrix` | Calculate a route matrix (Location and Geo Routes) |
| `CreateGeofenceCollection` | Create a geofence collection |
| `DescribeGeofenceCollection` | Describe a geofence collection |
| `UpdateGeofenceCollection` | Update a geofence collection |
| `DeleteGeofenceCollection` | Delete a geofence collection |
| `ListGeofenceCollections` | Page through the geofence collections |
| `PutGeofence` | Store a polygon, multipolygon or circle geofence |
| `GetGeofence` | Return a geofence |
| `ListGeofences` | Page through a collection's geofences |
| `BatchPutGeofence` | Store up to 10 geofences |
| `BatchDeleteGeofence` | Delete up to 10 geofences |
| `BatchEvaluateGeofences` | Evaluate positions and publish ENTER and EXIT events |
| `ForecastGeofenceEvents` | Forecast the geofence events a moving device will cause |
| `CreateTracker` | Create a tracker |
| `DescribeTracker` | Describe a tracker |
| `UpdateTracker` | Update a tracker |
| `DeleteTracker` | Delete a tracker |
| `ListTrackers` | Page through the trackers |
| `AssociateTrackerConsumer` | Link a geofence collection to a tracker |
| `DisassociateTrackerConsumer` | Unlink a geofence collection |
| `ListTrackerConsumers` | List a tracker's geofence collections |
| `BatchUpdateDevicePosition` | Store device positions, subject to position filtering |
| `GetDevicePosition` | Return a device's latest position |
| `GetDevicePositionHistory` | Page through a device's position history |
| `BatchGetDevicePosition` | Return several devices' latest positions |
| `ListDevicePositions` | List each device's latest position |
| `BatchDeleteDevicePositionHistory` | Delete devices' position history |
| `VerifyDevicePosition` | Return the inferred state of a device |
| `CreateKey` | Create a API key |
| `DescribeKey` | Describe a API key |
| `UpdateKey` | Update a API key |
| `DeleteKey` | Delete a API key |
| `ListKeys` | Page through the API keys |
| `StartJob` | Start an address validation job |
| `GetJob` | Return a job |
| `ListJobs` | Page through jobs |
| `CancelJob` | Cancel a job that hasn't finished |
| `GetStyleDescriptor` | Return a Geo Maps style descriptor |
| `GetGlyphs` | Return a Geo Maps glyph range |
| `GetSprites` | Return a Geo Maps sprite file |
| `GetTile` | Return a Geo Maps tile |
| `GetStaticMap` | Draw a static map PNG |
| `Autocomplete` | Complete a partial address |
| `Geocode` | Geocode an address or components |
| `ReverseGeocode` | Find the address at a position |
| `SearchNearby` | Find places near a position |
| `SearchText` | Search places by text |
| `Suggest` | Suggest places and queries |
| `CalculateRoutes` | Calculate Geo Routes routes |
| `CalculateIsolines` | Calculate reachable areas |
| `OptimizeWaypoints` | Order waypoints by nearest neighbor |
| `SnapToRoads` | Snap a GPS trace |
<!-- floci:actions:end -->

## Resources

Maps, place indexes, route calculators, geofence collections, trackers and API keys are stored per account and Region. They answer `ConflictException` for a duplicate name and `ResourceNotFoundException` (HTTP 404) for a missing one, and are tagged through `/tags/{ResourceArn}`.

- A map's `DataSource` follows its style (`VectorEsriNavigation` is `Esri`, `VectorHere…` is `Here`, and so on).
- A place index or route calculator takes `Esri`, `Here` or `Grab`.
- A tracker's `PositionFiltering` defaults to `TimeBased`.
- An API key takes exactly one of `ExpireTime` and `NoExpiry`, and its value is `v1.public.<random>`. Data-plane calls accept a `key` query parameter without checking it.

Requests are checked against the API model's constraints. One exception: API key `AllowResources` accept wildcard ARNs such as `arn:aws:geo:*:*:map/*`, which AWS accepts although the model's pattern does not.

## Geofences and tracking

Geofences are polygons (closed rings of at least four positions, holes allowed), multipolygons or circles; a `Geobuf` geometry is stored but never contains a position. `BatchEvaluateGeofences` compares each position with the device's last state in the collection. It publishes a `Location Geofence Event` (source `aws.geo`, `EventType` `ENTER` or `EXIT`) to the default EventBridge bus for every geofence the device crossed. `ForecastGeofenceEvents` reports `ENTER` for geofences the device can reach within the time horizon at its speed, `EXIT` for ones it is in and can leave, and `IDLE` for ones it can't leave in time. Each forecast carries the distance to the edge and the breach time.

`BatchUpdateDevicePosition` evaluates each update against the tracker's linked geofence collections (`AssociateTrackerConsumer`), then stores it subject to the tracker's position filtering:

- `TimeBased` keeps one update per 30 seconds of sample time.
- `DistanceBased` keeps an update only after 30 m of movement.
- `AccuracyBased` keeps one only when the movement exceeds both updates' horizontal accuracy.

Thirty days of history are kept. With `EventBridgeEnabled` each stored update publishes a `Location Device Position Event`. `VerifyDevicePosition` has no network-location data, so it infers the reported position, with no deviation and no proxy.

## Places

The gazetteer holds about 95 world cities and 28 landmarks and points of interest (Space Needle, Pike Place Market, Seattle coffee shops, the White House, the Eiffel Tower, and more), each with an address and a time zone. Every word of a query must match a place's name, street, city, region, country or a category word (`coffee`, `museum`, `park`, `airport`, `hotel`, …). Results near the bias position rank first. A street address followed by a known city (`100 Main St, Seattle`) is answered with an interpolated address in that city.

- **Location place indexes** return `PlaceId`s only for `Here` and `Grab` indexes, as AWS does, so `GetPlace` on an `Esri` index is a `ValidationException`. `SearchPlaceIndexForPosition` returns the landmark within 250 m, or else the nearest city, then the places nearby.
- **Geo Places** (`geocode`, `reverse-geocode`, `search-text`, `search-nearby`, `autocomplete`, `suggest`, `place/{PlaceId}`) returns the v2 shapes with `PlaceType`, an `Address` with ISO country codes, categories and the time zone. `SearchText` and `Suggest` need a bias position, bounding box or circle.

## Routes

A leg's distance is the great-circle distance times 1.25, and its duration is that distance at the travel mode's typical speed: car and motorcycle 50 km/h, truck 45, scooter 30, bicycle 15, walking 5.

- **Location route calculators** return legs with steps and, with `IncludeLegGeometry`, the line. Distances are in the requested `DistanceUnit`. An `Esri` route matrix is at most 10 by 10.
- **Geo Routes** returns routes with vehicle or pedestrian leg details, route matrices, isolines (a circle of the reachable radius), waypoint sequences (nearest neighbor from the origin), and road snapping (every trace point is already on a road). Geometry defaults to HERE's flexible polyline; `Simple` returns GeoJSON lines.

## Maps

Style descriptors are MapLibre styles whose sources point at the map's own tile route. Vector tiles are Mapbox Vector Tiles with a `land` layer covering the tile; raster and satellite tiles are solid PNGs. Glyph ranges are valid glyph PBFs with no glyphs, and sprites are an empty index and a transparent PNG. Geo Maps' `GetStaticMap` draws a PNG of the requested size with a graticule and a marker at the center.

## Jobs

`StartJob` (`ValidateAddress`) needs an execution role that exists in IAM (otherwise `AccessDeniedException`) and Parquet input and output in S3. The job reads the input through Floci's DuckDB sidecar and looks each row's address up in the gazetteer. It writes `<output>/<JobId>/output.parquet` with the input columns plus `Validated`, `FormattedAddress`, `Longitude` and `Latitude`. The job is `Pending`, `Running`, then `Completed`, or `Failed` with an `Error`. `CancelJob` stops a job that hasn't finished.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_LOCATION_ENABLED` | `true` | Enable or disable Location and the Geo APIs |

For the upstream API shape, see the [Amazon Location Service API Reference](https://docs.aws.amazon.com/location/latest/APIReference/Welcome.html).
