# AWS HealthImaging

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/datastore/...`, signed for `medical-imaging`

Floci imports DICOM into image sets, stores their frames as HTJ2K or JPEG 2000 lossless, and keeps every version of
their metadata.

## Data stores

A data store is `CREATING`, then `ACTIVE`. Only an empty one can be deleted: it's `DELETING`, then `DELETED`, then
gone. Its lossless storage format, `HTJ2K` (the default) or `JPEG_2000_LOSSLESS`, sets how frames are stored. The same
client token gives the same data store back.

## Import jobs

`StartDICOMImportJob` is `SUBMITTED`, then `IN_PROGRESS`. The job assumes its data access role, which must exist and
trust `medical-imaging.amazonaws.com`, and reads every object under its input URI as a DICOM Part 10 file:

- **Data sets:** explicit or implicit VR, little or big endian, or deflated, with nested sequences.
- **Pixel data:** native, RLE, JPEG lossless, JPEG-LS or JPEG 2000 lossless frames are stored as the data store's
  format. JPEG baseline and extended, JPEG 2000, HTJ2K and MPEG frames are stored as they are.

Instances are grouped into image sets by study and series. A series already in the data store gets a new version of its
primary image set. Instances that conflict with it (another patient, or a changed instance) go to a new image set that
isn't primary. Each frame gets its checksum at every resolution, its pixel range and its size. A DICOM JSON metadata
import configuration overrides the patient, study and series attributes of the series it maps.

The job then writes `job-output-manifest.json` and its `SUCCESS/success.ndjson` and `FAILURE/failure.ndjson` lists
under `<outputS3Uri>/<jobId>/`, and is `COMPLETED`. Files that aren't DICOM are listed as failures. A role that can't be
assumed, or input that can't be read, makes the job `FAILED`.

Frames are encoded with OpenJPH, OpenJPEG and DCMTK, installed from Debian's packages into a container on first use, so
importing pixel data needs Docker. Instances without pixel data import without it.

## Image sets

An image set's metadata is HealthImaging's JSON: patient, study, series and instance attributes by keyword, with each
instance's image frames. `GetImageSetMetadata` returns a version's metadata gzipped. `GetImageFrame` returns a frame as
stored. Both record the access time, and an image set unread for 30 days is in the archive instant access tier.
`SearchImageSets` filters on patient, study, series, accession number, study date and time, creation and update times
and whether an image set is primary, and sorts its results.

`UpdateImageSetMetadata` updates or removes attributes, or reverts to an earlier version. The latest version must match.
Changing a study, series or SOP instance UID needs `force`. With `includeStudyImageSets`, patient and study changes
apply to the study's other image sets too. `CopyImageSet` copies a source image set's latest version, or the instances
its copiable attributes name, into a new image set or into another one, and can promote the copy to primary.
`DeleteImageSet` deletes an image set and the frames no other image set references. Updates, copies and deletes lock
the image sets they change, then record a new version or mark the image set `DELETED`. Data stores and image sets take
tags.

## Events

Data store, import job and image set state changes go to the default event bus from `aws.medical-imaging`, as
`Data Store Created`, `Import Job Completed`, `Image Set Updated` and the other HealthImaging detail types.

## Configuration

```yaml
floci:
  services:
    medical-imaging:
      enabled: true
      transition-millis: 2000          # how long transitional states last
      codec-image: debian:trixie-slim  # where the frame codecs are installed
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CopyImageSet` | Copy an image set, or the instances named, into a new or existing image set |
| `CreateDatastore` | Create a data store with its lossless storage format |
| `DeleteDatastore` | Delete an empty data store |
| `DeleteImageSet` | Delete an image set and its unshared frames |
| `GetDICOMImportJob` | Get import job |
| `GetDatastore` | Get data store |
| `GetImageFrame` | An image frame as stored |
| `GetImageSet` | Get an image set, or one of its versions |
| `GetImageSetMetadata` | A version's metadata, gzipped |
| `ListDICOMImportJobs` | List a data store's import jobs |
| `ListDatastores` | List data stores |
| `ListImageSetVersions` | List an image set's versions |
| `ListTagsForResource` | List a resource's tags |
| `SearchImageSets` | Filter and sort image sets |
| `StartDICOMImportJob` | Import DICOM files from S3 |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateImageSetMetadata` | Update or remove attributes, or revert to a version |
<!-- floci:actions:end -->
