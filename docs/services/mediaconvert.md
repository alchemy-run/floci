# AWS Elemental MediaConvert

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2017-08-29/...`, signed for `mediaconvert`

Floci runs MediaConvert jobs with ffmpeg: a job reads its inputs from S3 (or HTTP and HTTPS), transcodes them into
its output groups, and writes the results to their S3 destinations.

## Queues, presets and job templates

Every Region has the `Default` queue, which can't be deleted. Queues are on-demand (with a number of concurrent
jobs) or reserved (a reservation plan's slots), and `ACTIVE` or `PAUSED`; a paused queue keeps its jobs `SUBMITTED`.
A queue with jobs that haven't finished can't be deleted. Presets and job templates are checked for their codecs and
the presets their outputs name. A few system presets and job templates (`System-...`) exist in every Region, listed
with `ListBy: SYSTEM`; they can be used but not changed. Queues, presets, job templates and jobs take tags.

## Jobs

A job runs its template's settings with its own over them, and each output's preset under the output's own settings.
Its role must be an IAM role that exists. Its inputs must be `s3://`, `http://` or `https://` URLs the account's policy
(`PutPolicy`) allows. It is `SUBMITTED` to its queue, and runs when the queue is active and has room, the highest
priority first. While it runs it is `PROGRESSING`, through the phases `PROBING`, `TRANSCODING` and `UPLOADING`. It
ends `COMPLETE`, with each output's duration and frame size in `OutputGroupDetails`, or `ERROR` with MediaConvert's
error code: 1030 for an input that can't be read, 1010 for a failed transcode, 1404 for an output that can't be
written. `CancelJob` cancels a submitted or progressing job. Every state change, the input information and the output
details are sent to the default event bus as `MediaConvert Job State Change` events.

Several inputs are stitched in order, each scaled to the first's frame size, and an input's first clipping trims it.
Output groups make:

- **File group:** one file per output, named after the input (or the destination's file name) and the output's name
  modifier, in its container (MP4, MOV, MXF, M2TS, WebM, MKV, Ogg, F4V, ISMV, GIF, Y4M, or raw audio and video).
  Frame captures make a JPEG sequence (`name.0000000.jpg`) at their rate, up to their maximum captures.
- **HLS group:** a playlist and TS segments per output, and a main playlist with each rendition's bandwidth and
  resolution.
- **DASH ISO group:** an MPD with each output as a representation.
- **CMAF group:** fragmented MP4 renditions with HLS playlists and an MPD.
- **Microsoft Smooth group:** an ISM.

Video takes its width, height, scaling behavior, crop and frame rate. H.264, H.265, AV1, VP8, VP9, MPEG-2, ProRes,
VC-3, GIF and uncompressed video map to ffmpeg's encoders, with the bitrate, rate control (QVBR as a constant rate
factor from the quality level), profile, GOP and B-frames. AAC, MP3, MP2, AC3, EAC3, Opus, Vorbis, FLAC, WAV and AIFF
audio take their bitrate, sample rate and channels. Captions, watermarks, image inserters, DRM encryption and
acceleration are accepted but not applied. Running jobs needs Docker: ffmpeg runs in the image Kinesis Video Streams
transcodes with.

`ListJobs`, `SearchJobs` (by input file too) and jobs queries (`StartJobsQuery`, `GetJobsQueryResults`) filter jobs by
queue, status, input, engine version and codec. `Probe` reads files with ffprobe: their container, duration, bitrate
and tracks, with each video track's size, frame rate and color, and each audio track's channels and sample rate.

## Configuration

```yaml
floci:
  services:
    media-convert:
      enabled: true
      concurrent-jobs: 20      # an on-demand queue's concurrent jobs, unless set
      max-running-jobs: 2      # jobs transcoding at once, across every queue
      max-queues: 10
      transition-millis: 2000  # a jobs query takes a quarter of this
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateCertificate` | Associate an ACM certificate |
| `CancelJob` | Cancel a submitted or progressing job |
| `CreateJob` | Submit a job; it transcodes with ffmpeg |
| `CreateJobTemplate` | Create a job template |
| `CreatePreset` | Create an output preset |
| `CreateQueue` | Create a queue |
| `CreateResourceShare` | Share a job with AWS Support |
| `DeleteJobTemplate` | Delete a job template |
| `DeletePolicy` | Reset the input policy |
| `DeletePreset` | Delete a preset |
| `DeleteQueue` | Delete a queue with no unfinished jobs |
| `DescribeEndpoints` | The endpoint jobs are sent to |
| `DisassociateCertificate` | Disassociate a certificate |
| `GetJob` | Describe a job |
| `GetJobTemplate` | Describe a job template |
| `GetJobsQueryResults` | A jobs query's results |
| `GetPolicy` | The account's input policy |
| `GetPreset` | Describe a preset |
| `GetQueue` | Describe a queue |
| `ListJobTemplates` | List job templates, custom or system |
| `ListJobs` | List jobs by queue and status |
| `ListPresets` | List presets, custom or system |
| `ListQueues` | List queues |
| `ListTagsForResource` | List a resource's tags |
| `ListVersions` | List job engine versions |
| `Probe` | Read files' containers and tracks with ffprobe |
| `PutPolicy` | Allow or disallow S3, HTTP and HTTPS inputs |
| `SearchJobs` | Search jobs, by input file too |
| `StartJobsQuery` | Start a filtered jobs query |
| `TagResource` | Tag a queue, preset, job template or job |
| `UntagResource` | Remove tags |
| `UpdateJobTemplate` | Update a job template |
| `UpdatePreset` | Update a preset |
| `UpdateQueue` | Update or pause a queue |
<!-- floci:actions:end -->
