/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.util.graph;

import org.apache.calcite.util.Util;

import java.util.List;

/**
 * Directed graph where edges have attributes and allows multiple edges between
 * any two vertices provided that their attributes are different.
 *
 * @param <V> Vertex type
 * @param <E> Edge type
 */
public class AttributedDirectedGraph<V, E extends DefaultEdge>
    extends DefaultDirectedGraph<V, E> {
  /** Creates an attributed graph. */
  public AttributedDirectedGraph(AttributedEdgeFactory<V, E> edgeFactory) {
    super(edgeFactory);
  }

  public static <V, E extends DefaultEdge> AttributedDirectedGraph<V, E> create(
      AttributedEdgeFactory<V, E> edgeFactory) {
    return new AttributedDirectedGraph<>(edgeFactory);
  }

  /** Returns the first edge between one vertex to another. */
  @Override public E getEdge(V source, V target) {
    final List<E> outEdges = vertexOutMap.get(source);
    for (E outEdge : outEdges) {
      if (outEdge.target.equals(target)) {
        return outEdge;
      }
    }
    return null;
  }

  /** @deprecated Use {@link #addEdge(Object, Object, Object...)}. */
  @Deprecated
  public E addEdge(V vertex, V targetVertex) {
    return super.addEdge(vertex, targetVertex);
  }

  public E addEdge(V vertex, V targetVertex, Object... attributes) {
    final List<E> outEdges = vertexOutMap.get(vertex);
    if (outEdges == null) {
      throw new IllegalArgumentException("no vertex " + vertex);
    }
    final List<E> inEdges = vertexInMap.get(targetVertex);
    if (inEdges == null) {
      throw new IllegalArgumentException("no vertex " + targetVertex);
    }
    @SuppressWarnings("unchecked")
    final AttributedEdgeFactory<V, E> f =
        (AttributedEdgeFactory) this.edgeFactory;
    final E edge = f.createEdge(vertex, targetVertex, attributes);
    if (edges.add(edge)) {
      outEdges.add(edge);
      inEdges.add(edge);
      return edge;
    } else {
      return null;
    }
  }

  /** Returns all edges between one vertex to another. */
  public Iterable<E> getEdges(V source, final V target) {
    final List<E> edges = vertexOutMap.get(source);
    return Util.filter(edges, outEdge -> outEdge.target.equals(target));
  }

  /** Removes all edges from a given vertex to another.
   * Returns whether any were removed. */
  public boolean removeEdge(V source, V target) {
    // remove out edges
    final List<E> outEdges = vertexOutMap.get(source);
    int removeOutCount = 0;
    for (int i = 0, size = outEdges.size(); i < size; i++) {
      E edge = outEdges.get(i);
      if (edge.target.equals(target)) {
        outEdges.remove(i);
        edges.remove(edge);
        ++removeOutCount;
      }
    }

    // remove inedges
    final List<E> inEdges = vertexInMap.get(target);
    int removeInCount = 0;
    for (int i = 0, size = inEdges.size(); i < size; i++) {
      E edge = inEdges.get(i);
      if (edge.source.equals(source)) {
        inEdges.remove(i);
        ++removeInCount;
      }
    }

    assert removeOutCount == removeInCount;
    return removeOutCount > 0;
  }

  /** Factory for edges that have attributes.
   *
   * @param <V> Vertex type
   * @param <E> Edge type
   */
  public interface AttributedEdgeFactory<V, E> extends EdgeFactory<V, E> {
    E createEdge(V v0, V v1, Object... attributes);
  }
}
