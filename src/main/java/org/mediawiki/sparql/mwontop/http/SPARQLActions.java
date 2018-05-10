/*
 * Copyright (c) 2017 MW2SPARQL developers.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mediawiki.sparql.mwontop.http;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleIRI;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.resultio.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.*;
import org.mediawiki.sparql.mwontop.sql.RepositoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import static org.mediawiki.sparql.mwontop.http.MWNamespace.transformNamespace;

/**
 * @author Thomas Pellissier Tanon
 */
@Path("/sparql")
public class SPARQLActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(SPARQLActions.class);
    private static Repository REPOSITORY = RepositoryFactory.getInstance().getRepository();

    @GET
    public Response get(@QueryParam("query") String query, @Context Request request) {
        if (query == null) {
            throw new BadRequestException("You should set a SPARQL query using the 'query' URL query parameter");
        }
        return executeQuery(query, null, request);
    }

    @POST
    @Consumes({"application/x-www-form-urlencoded", "multipart/form-data"})
    public Response postForm(@FormParam("query") String query, @Context Request request) {
        if (query == null) {
            throw new BadRequestException("You should POST a SPARQL query with the application/sparql-query content type");
        }
        return executeQuery(query, null, request);
    }

    @POST
    @Consumes("application/sparql-query")
    public Response postDirect(String query, @Context Request request) {
        return executeQuery(query, null, request);
    }

    private Response executeQuery(String queryString, String baseIRI, Request request) {
        try {
            RepositoryConnection repositoryConnection = REPOSITORY.getConnection();
            try {
                Query query = repositoryConnection.prepareQuery(QueryLanguage.SPARQL, transformNamespace(queryString));
                if (query instanceof BooleanQuery) {
                    return evaluateBooleanQuery((BooleanQuery) query, request);
                } else if (query instanceof GraphQuery) {
                    return evaluateGraphQuery((GraphQuery) query, request);
                } else if (query instanceof TupleQuery) {
                    return evaluateTupleQuery((TupleQuery) query, request);
                } else {
                    throw new BadRequestException("Unsupported kind of query: " + queryString);
                }
            } finally {
                repositoryConnection.close();
            }
        } catch (MalformedQueryException e) {
            LOGGER.info(e.getMessage(), e);
            throw new BadRequestException(e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.warn(e.getMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        }
    }

    private Response evaluateBooleanQuery(BooleanQuery query, Request request) {
        RDFContentNegotiation.FormatService<BooleanQueryResultWriterFactory> format =
                RDFContentNegotiation.getServiceForFormat(BooleanQueryResultWriterRegistry.getInstance(), request);
        return Response.ok(
                (StreamingOutput) outputStream -> {
                    try {
                        format.getService().getWriter(outputStream).handleBoolean(query.evaluate());
                    } catch (QueryResultHandlerException | QueryEvaluationException e) {
                        LOGGER.warn(e.getMessage(), e);
                        throw new InternalServerErrorException(e.getMessage(), e);
                    }
                },
                RDFContentNegotiation.variantForFormat(format.getFormat())
        ).build();
    }

    private Response evaluateGraphQuery(GraphQuery query, Request request) {
        RDFContentNegotiation.FormatService<RDFWriterFactory> format =
                RDFContentNegotiation.getServiceForFormat(RDFWriterRegistry.getInstance(), request);
        return Response.ok(
                (StreamingOutput) outputStream -> {
                    try {
                        query.evaluate(format.getService().getWriter(outputStream));
                    } catch (RDFHandlerException | QueryEvaluationException e) {
                        LOGGER.warn(e.getMessage(), e);
                        throw new InternalServerErrorException(e.getMessage(), e);
                    }
                },
                RDFContentNegotiation.variantForFormat(format.getFormat())
        ).build();
    }

    private Response evaluateTupleQuery(TupleQuery query, Request request) {
        RDFContentNegotiation.FormatService<TupleQueryResultWriterFactory> format =
                RDFContentNegotiation.getServiceForFormat(TupleQueryResultWriterRegistry.getInstance(), request);
        return Response.ok(
                (StreamingOutput) outputStream -> {
                    try {
                        TupleQueryResultWriter writer = format.getService().getWriter(outputStream);
                        TupleQueryResult result = query.evaluate();

                        writer.startQueryResult(result.getBindingNames());
                        while (result.hasNext()) {
                            BindingSet oldSet = result.next();
                            MapBindingSet newSet = new MapBindingSet();
                            for (Binding binding : oldSet) {
                                Value value = binding.getValue();
                                if (binding.getValue() instanceof SimpleIRI) {
                                    String input = value.stringValue();
                                    value = SimpleValueFactory.getInstance().createIRI(transformNamespace(input));
                                }
                                newSet.addBinding(binding.getName(), value);
                            }
                            writer.handleSolution(newSet);
                        }
                        writer.endQueryResult();


                    } catch (TupleQueryResultHandlerException | QueryEvaluationException e) {
                        LOGGER.warn(e.getMessage(), e);
                        throw new InternalServerErrorException(e.getMessage(), e);
                    }
                },
                RDFContentNegotiation.variantForFormat(format.getFormat())
        ).build();
    }
}
