/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.set.aphrodite.stream.services.json;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.set.aphrodite.Aphrodite;
import org.jboss.set.aphrodite.common.Utils;
import org.jboss.set.aphrodite.config.AphroditeConfig;
import org.jboss.set.aphrodite.config.StreamConfig;
import org.jboss.set.aphrodite.config.StreamType;
import org.jboss.set.aphrodite.domain.Codebase;
import org.jboss.set.aphrodite.domain.Repository;
import org.jboss.set.aphrodite.domain.Stream;
import org.jboss.set.aphrodite.domain.StreamComponent;
import org.jboss.set.aphrodite.spi.NotFoundException;
import org.jboss.set.aphrodite.spi.StreamService;

/**
 * A stream service which reads stream data from the specified JSON file.  This implementation
 * assumes that streams are written in order in the json file, i.e. the most recent (upstream) issue
 * is specified as the first JSON object in the "streams" JSON array. An example JSON file can be
 * found at https://github.com/jboss-set/jboss-streams
 *
 * @author Ryan Emerson
 */
public class JsonStreamService implements StreamService {
    private static final Log LOG = LogFactory.getLog(JsonStreamService.class);

    private final Map<String, Stream> streamMap = new HashMap<>();
    private Aphrodite aphrodite;

    @Override
    public boolean init(Aphrodite aphrodite, AphroditeConfig config) throws NotFoundException {
        this.aphrodite = aphrodite;
        Iterator<StreamConfig> i = config.getStreamConfigs().iterator();
        while (i.hasNext()) {
            StreamConfig streamConfig = i.next();
            if (streamConfig.getStreamType() == StreamType.JSON) {
                i.remove();
                return init(streamConfig);
            }
        }
        return false;
    }

    private boolean init(StreamConfig config) throws NotFoundException {
        if (config.getURL().isPresent()) {
            readJsonFromURL(config.getURL().get());
        } else if (config.getStreamFile().isPresent()) {
            readJsonFromFile(config.getStreamFile().get());
        } else {
            throw new IllegalArgumentException("StreamConfig requires either a URL or File to be specified");
        }
        return true;
    }

    @Override
    public List<Stream> getStreams() {
        return new ArrayList<>(streamMap.values());
    }

    @Override
    public Stream getStream(String streamName) {
        return streamMap.get(streamName);
    }

    private void readJsonFromFile(File file) throws NotFoundException {
        try (JsonReader jr = Json.createReader(new FileInputStream(file))) {
            parseJson(jr.readObject());
        } catch (IOException e) {
            Utils.logException(LOG, "Unable to load file: " + file.getPath(), e);
            throw new NotFoundException("Unable to load file: " + file.getPath(), e);
        } catch (JsonException e) {
            Utils.logException(LOG, e);
            throw new NotFoundException(e);
        }
    }

    private void readJsonFromURL(URL url) throws NotFoundException {
        try (InputStream is = url.openStream()) {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            JsonReader jr = Json.createReader(rd);
            parseJson(jr.readObject());
        } catch (IOException | NotFoundException e) {
            Utils.logException(LOG, "Unable to load url: " + url.toString(), e);
            throw new NotFoundException(e);
        }
    }

    private void parseJson(JsonObject jsonObject) throws NotFoundException {
        JsonArray jsonArray = jsonObject.getJsonArray("streams");
        Objects.requireNonNull(jsonArray, "streams array must be specified in json file");

        for (JsonValue value : jsonArray) {
            JsonObject json = (JsonObject) value;

            String upstreamName = json.getString("upstream", null);
            Stream upstream = streamMap.get(upstreamName);

            JsonArray codebases = json.getJsonArray("codebases");
            Map<String, StreamComponent> codebaseMap = parseStreamCodebases(codebases);

            Stream currentStream = new Stream(json.getString("name"), upstream, codebaseMap);
            streamMap.put(currentStream.getName(), currentStream);
        }
    }

    private Map<String, StreamComponent> parseStreamCodebases(JsonArray codebases) throws NotFoundException {
        Map<String, StreamComponent> codebaseMap = new HashMap<>();
        for (JsonValue value : codebases) {
            JsonObject json = (JsonObject) value;
            String componentName = json.getString("component_name");
            String codebaseName = json.getString("codebase");
            URL repositoryUrl = parseUrl(json.getString("repository_url"));
            // ignore until it supports svn repository
            if (!repositoryUrl.toString().contains("svn.jboss.org")) {
                Repository repository = aphrodite.getRepository(repositoryUrl);
                Codebase codebase = new Codebase(codebaseName);
                if (!repository.getCodebases().contains(codebase)) {
                    Utils.logWarnMessage(LOG, "The specified codebase '" + codebaseName + "' " +
                            "does not belong to the Repository at " + repository.getURL());
                } else {
                    StreamComponent component = new StreamComponent(componentName, repository, codebase);
                    codebaseMap.put(component.getName(), component);
                }
            }
        }
        return codebaseMap;
    }

    private URL parseUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new JsonException("Unable to parse url: " + e.getMessage());
        }
    }

    @Override
    public List<URL> getAllRepositoryURLs() {
        List<URL> repositories = new ArrayList<>();

        List<Stream> streams = getStreams();
        for (Stream stream : streams) {
            repositories.addAll(getRepositoryURLsByStream(stream.getName()).stream()
                    .filter(e -> !repositories.contains(e))
                    .collect(Collectors.toList()));
        }

        return repositories;
    }

    @Override
    public List<URL> getRepositoryURLsByStream(String streamName) {
        return getStream(streamName).getAllComponents().stream()
                .map((e) -> e.getRepository().getURL())
                .collect(Collectors.toList());
    }

    @Override
    public List<Stream> getStreamsBy(Repository repository, Codebase codebase) {
        List<Stream> streams = new ArrayList<>();
        for (Stream stream : getStreams()) {
            for (StreamComponent sc : stream.getAllComponents()) {
                if (sc.getRepository().equals(repository) && sc.getCodebase().equals(codebase)) {
                    if (!streams.contains(stream)) {
                        streams.add(stream);
                    }
                }
            }
        }

        return streams;
    }

    @Override
    public String getComponentNameBy(Repository repository, Codebase codebase) {
        for (Stream stream : getStreams()) {
            for (StreamComponent sc : stream.getAllComponents()) {
                if (sc.getRepository().equals(repository) && codebase.equals(sc.getCodebase())) {
                    return sc.getName();
                }
            }
        }

        return repository.getURL().toString();
    }

}
