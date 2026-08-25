package com.orchpilot.pluginserver.storage;

import com.orchpilot.pluginserver.exception.PluginServerException;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

/**
 * Where plugin archives live.
 *
 * <h2>Why GridFS rather than a document field</h2>
 *
 * <p>A plugin archive with shaded dependencies is routinely tens of megabytes, and MongoDB documents are capped at
 * 16MB. GridFS chunks the bytes and streams them, so a download does not require the whole archive in the
 * service's heap. That last property is not a nicety: a registry serving a dozen concurrent installs of a 50MB
 * plugin would otherwise need most of a gigabyte of heap to do it.
 *
 * <h2>Why not a filesystem or object store</h2>
 *
 * <p>It would work, and it would add a second thing to back up, secure and keep consistent with the metadata. One
 * datastore means a version's record and its bytes are lost or kept together. Should this outgrow GridFS, the
 * interface to change is this one class.
 */
@Component
public class GridFsPluginStorage {

    private static final Logger log = LoggerFactory.getLogger(GridFsPluginStorage.class);

    private final GridFsTemplate gridFs;

    public GridFsPluginStorage(GridFsTemplate gridFs) {
        this.gridFs = gridFs;
    }

    /**
     * Stores an archive.
     *
     * <p>The metadata written alongside the bytes duplicates what the version document holds. That is deliberate:
     * it makes an orphaned file identifiable. Without it, a crash between storing bytes and writing the version
     * leaves an anonymous blob that nothing can attribute or safely delete.
     *
     * @param pluginId  owning plugin
     * @param version   version being stored
     * @param fileName  original upload name, for the download's Content-Disposition
     * @param checksum  SHA-256 of the content, in lower-case hex
     * @param content   the archive
     * @return the GridFS file id
     */
    public String store(String pluginId, String version, String fileName, String checksum,
                        InputStream content) {
        Document metadata = new Document()
                .append("pluginId", pluginId)
                .append("version", version)
                .append("sha256", checksum)
                .append("contentType", "application/java-archive");

        String fileId = gridFs.store(content, storedName(pluginId, version), metadata).toHexString();
        log.info("Stored archive for {}:{} as GridFS file {}", pluginId, version, fileId);
        return fileId;
    }

    /**
     * Opens an archive for streaming.
     *
     * @param fileId GridFS file id from the version record
     * @return the resource, or empty when the file is not there
     */
    public Optional<GridFsResource> open(String fileId) {
        GridFSFile file = gridFs.findOne(Query.query(Criteria.where("_id").is(fileId)));
        return file == null ? Optional.empty() : Optional.of(gridFs.getResource(file));
    }

    /**
     * Opens an archive, or fails with the error the caller should see.
     *
     * @param pluginId owning plugin, for the message
     * @param version  version, for the message
     * @param fileId   GridFS file id
     * @return the resource
     * @throws PluginServerException when the record exists but the bytes do not
     */
    public GridFsResource require(String pluginId, String version, String fileId) {
        return open(fileId).orElseThrow(() -> {
            // The metadata promised bytes that are not there. That is the registry's failure, not the caller's,
            // and it is worth an error-level line because somebody has to go and look.
            log.error("Version {}:{} references GridFS file {}, which is missing", pluginId, version, fileId);
            return PluginServerException.archiveMissing(pluginId, version);
        });
    }

    /**
     * Deletes an archive.
     *
     * <p>Tolerates an already-absent file, so deleting a version whose bytes were lost still succeeds. Refusing
     * would leave a record nothing can remove.
     *
     * @param fileId GridFS file id
     */
    public void delete(String fileId) {
        if (fileId == null) {
            return;
        }
        try {
            gridFs.delete(Query.query(Criteria.where("_id").is(fileId)));
        } catch (RuntimeException ex) {
            log.warn("Could not delete GridFS file {}: {}", fileId, ex.getMessage());
        }
    }

    /**
     * @param fileId GridFS file id
     * @return whether the bytes are present
     */
    public boolean exists(String fileId) {
        return fileId != null && gridFs.findOne(Query.query(Criteria.where("_id").is(fileId))) != null;
    }

    /**
     * The name a file is stored under.
     *
     * <p>The coordinate rather than the uploaded file name, which is chosen by whoever uploaded and is neither
     * unique nor trustworthy. The name a client sees on download comes from the version record.
     */
    private static String storedName(String pluginId, String version) {
        return pluginId + "-" + version + ".jar";
    }
}
