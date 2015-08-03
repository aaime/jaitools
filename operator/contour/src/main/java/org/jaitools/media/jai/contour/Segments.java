/* 
 *  Copyright (c) 2010, Michael Bedward. All rights reserved. 
 *   
 *  Redistribution and use in source and binary forms, with or without modification, 
 *  are permitted provided that the following conditions are met: 
 *   
 *  - Redistributions of source code must retain the above copyright notice, this  
 *    list of conditions and the following disclaimer. 
 *   
 *  - Redistributions in binary form must reproduce the above copyright notice, this 
 *    list of conditions and the following disclaimer in the documentation and/or 
 *    other materials provided with the distribution.   
 *   
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
 *  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR 
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON 
 *  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS 
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
 */   

package org.jaitools.media.jai.contour;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.jaitools.media.jai.contour.Segment.MergePoint;

import com.vividsolutions.jts.geom.LineString;


/**
 * A container for the segments collected by ContourOpImage. 
 * It will return them as merged lines eventually applying simplification procedures 
 * 
 * @author Andrea Aime - GeoSolutions
 * @since 1.1
 * @version $Id$
 */
class Segments {
    static final int MAX_SIZE = 16348; // this amounts to 130KB storage
    boolean simplify;

    Segment temp = new Segment();

    /**
     * List of segments sorted by start element
     */
    List<Segment> startList = new ArrayList<Segment>();

    /**
     * List of segments sorted by end element
     */
    List<Segment> endList = new ArrayList<Segment>();

    /**
     * The completed segment list
     */
    List<LineString> result = new ArrayList<LineString>();
    
    public Segments(boolean simplify) {
        this.simplify = simplify;
    }
    
    /**
     * Adds a segment to the mix
     * @param x1
     * @param y1
     * @param x2
     * @param y2
     */
    public void add(double x1, double y1, double x2, double y2) {
        // we don't add single points, only full segments
        if (Segment.samePoint(x1, y1, x2, y2)) {
            return;
        }

        // keep the lower ordinate first, it's the one that can connect with
        // previous segments
        if (y2 < y1) {
            double tmp = y1;
            y1 = y2;
            y2 = tmp;
            tmp = x1;
            x1 = x2;
            x2 = tmp;
        }
        
        // try to add using the lowest point first
        if (appendSegment(x1, y1, x2, y2)) {
            return;
        }
        // in case of same elevation lines, we might want to try the other end
        if (Segment.sameOrdinate(y2, y1) && appendSegment(x2, y2, x1, y1)) {
            return;
        }

        // no connection, need to create a new segment
        Segment segment = new Segment(x1, y1, x2, y2, simplify);
        insertInStartList(segment);
        insertInEndList(segment);
    }

    private boolean appendSegment(double x1, double y1, double x2, double y2) {
        temp.setXY(x1, y1, x1, y1);
        int startSegment = search(startList, temp, Segment.START_COMPARATOR);
        if (startSegment >= 0) {
            Segment segment = startList.remove(startSegment);
            segment.addBeforeStart(x2, y2);
            insertInStartList(segment);
            return true;
        } else {
            int endSegment = search(endList, temp, Segment.END_COMPARATOR);
            if (endSegment >= 0) {
                Segment segment = endList.remove(endSegment);
                segment.addAfterEnd(x2, y2);
                insertInEndList(segment);
                return true;
            }
        }
        temp.setXY(x2, y2, x2, y2);
        startSegment = search(startList, temp, Segment.START_COMPARATOR);
        if (startSegment >= 0) {
            Segment segment = startList.remove(startSegment);
            segment.addBeforeStart(x1, y1);
            insertInStartList(segment);
            return true;
        } else {
            int endSegment = search(endList, temp, Segment.END_COMPARATOR);
            if (endSegment >= 0) {
                Segment segment = endList.remove(endSegment);
                segment.addAfterEnd(x1, y1);
                insertInEndList(segment);
                return true;
            }
        }
        return false;
    }

    private void insertInEndList(Segment segment) {
        if (endList.isEmpty()) {
            endList.add(segment);
            return;
        }
        int insertAt = search(endList, segment, Segment.END_COMPARATOR);
        if (insertAt < 0) {
            insertAt = -insertAt - 1;
        }
        endList.add(insertAt, segment);
    }

    private void insertInStartList(Segment segment) {
        if (startList.isEmpty()) {
            startList.add(segment);
            return;
        }
        int insertAt = search(startList, segment, Segment.START_COMPARATOR);
        if (insertAt < 0) {
            insertAt = -insertAt - 1;
        }
        startList.add(insertAt, segment);
    }

    boolean sorted(List<Segment> list, Comparator<Segment> comparator) {
        Segment prev = null;
        for (Segment elem : list) {
            if (prev != null && comparator.compare(prev, elem) > 0) {
                return false;
            }
            prev = elem;
        }
        return true;
    }
    
    /**
     * Informs the segments a new scanline is started
     */
    public void lineComplete(int line) {
        // look for all the segments that have not been touched during the last scan
        for (int i = 0; i < startList.size();) {
            Segment segment = startList.get(i);
            // if touched, we can continue using it
            if (segment.touched) {
                segment.touched = false;
                i++;
                continue;
            }
            
            // if not, remove it from the search lists
            startList.remove(segment);
            endList.remove(segment);

            // can we merge it with an existing one?
            temp.setXY(segment.xStart, segment.yStart, segment.xStart, segment.yStart);
            Segment mergeTarget = null;
            MergePoint mergePoint = null;

            // end-start is the most efficient merge we can make, try it first
            int endSegment = search(endList, temp, Segment.END_COMPARATOR);
            if (endSegment >= 0) {
                mergeTarget = endList.get(endSegment);
                mergePoint = MergePoint.END_START;
            } else {
                int startSegment = search(startList, temp, Segment.START_COMPARATOR);
                if (startSegment >= 0) {
                    mergeTarget = startList.get(startSegment);
                    mergePoint = MergePoint.START_START;
                } else {
                    temp.setXY(segment.xEnd, segment.yEnd, segment.xEnd, segment.yEnd);
                    startSegment = search(startList, temp,
                            Segment.START_COMPARATOR);
                    if (startSegment >= 0) {
                        mergeTarget = startList.get(startSegment);
                        mergePoint = MergePoint.START_END;
                    } else {
                        endSegment = search(endList, temp,
                                Segment.END_COMPARATOR);
                        if (endSegment >= 0) {
                            mergeTarget = endList.get(endSegment);
                            mergePoint = MergePoint.END_END;
                        }
                    }
                }
            }

            if (mergeTarget != null) {
                mergeTarget.merge(segment, mergePoint);
                startList.remove(mergeTarget);
                insertInStartList(mergeTarget);
                endList.remove(mergeTarget);
                insertInEndList(mergeTarget);
            } else {
                LineString ls = segment.toLineString();
                result.add(ls);
            }
        }
    }

    int search(List<Segment> list, Segment reference, Comparator<Segment> comparator) {
        if (list.size() > 64) {
            return Collections.binarySearch(list, reference, comparator);
        } else {
            return linearSearch(list, reference, comparator);
        }
    }

    private int linearSearch(List<Segment> list, Segment reference,
            Comparator<Segment> comparator) {
        int i = 0;
        for (Segment segment : list) {
            int compare = comparator.compare(segment, reference);
            if (compare == 0) {
                return i;
            } else if (compare > 0) {
                return -i - 1;
            } else {
                i++;
            }
        }
        return (-i - 1);
    }

    /**
     * Returns the merged and eventually simplified segments
     * 
     * @return
     */
    public List<LineString> getMergedSegments() {
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Segments(").append(startList.size()).append(",")
                .append(result.size()).append(") ");
        sb.append("active=");
        for (Segment segment : startList) {
            sb.append(segment).append("\n");
        }
        sb.append("complete=");
        for (LineString ls : result) {
            sb.append(ls).append("\n");
        }
        return sb.toString();
    }

}
