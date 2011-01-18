/*
 * Copyright 2009 Michael Bedward
 * 
 * This file is part of jai-tools.

 * jai-tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.

 * jai-tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public 
 * License along with jai-tools.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
 
 /** 
  * Grammar for VarClassifier. 
  * 
  * Takes the AST produced by the Jiffle parser and checks for errors
  * with variables (e.g. use before initial assignment).
  *
  * @author Michael Bedward
  */

tree grammar VarClassifier;

options {
    tokenVocab = Jiffle;
    ASTLabelType = CommonTree;
}

@header {
package jaitools.jiffle.parser;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import jaitools.CollectionFactory;
import jaitools.jiffle.ErrorCode;
}

@members {

/*
 * Recording of user variables and checking that they are
 * assigned a value before being used in an expression.
 * Use of an unsassigned variable is not necessarily an error
 * as it might (should) be an input image variable.
 */
private Set<String> userVars = CollectionFactory.set();

public Set<String> getUserVars() {
    return userVars;
}

private Set<String> unassignedVars = CollectionFactory.set();

public Set<String> getUnassignedVars() {
    return unassignedVars;
}

public boolean hasUnassignedVar() {
    return !unassignedVars.isEmpty();
}

/**
 * Image var validation - there should be at least one output image
 * and no image should be used for both input and output
 */
private Set<String> imageVars;

public void setImageVars(Collection<String> varNames) {
    imageVars = CollectionFactory.set();
    imageVars.addAll(varNames);
}

private Set<String> inImageVars = CollectionFactory.set();
private Set<String> outImageVars = CollectionFactory.set();

public Set<String> getOutputImageVars() {
    return outImageVars;
}

private Set<String> nbrRefVars = CollectionFactory.set();


/* Table of var name : error code */
private Map<String, ErrorCode> errorTable = CollectionFactory.orderedMap();

public Map<String, ErrorCode> getErrors() {
    return errorTable;
}

/* Check for errors */
public boolean hasError() {
    for (ErrorCode code : errorTable.values()) {
        if (code.isError()) return true;
    }
    
    return false;
}

/* Check for warnings */
public boolean hasWarning() {
    for (ErrorCode code : errorTable.values()) {
        if (!code.isError()) return true;
    }
    
    return false;
}

/*
 * This method is run after the tree has been processed to 
 * check that the image var params and the AST are in sync
 */
private void postValidation() {
    for (String varName : unassignedVars) {
        errorTable.put(varName, ErrorCode.VAR_UNDEFINED);
    }

    if (outImageVars.isEmpty()) {
        errorTable.put("n/a", ErrorCode.IMAGE_NO_OUT);
    }

    // check all image vars are accounted for
    for (String varName : imageVars) {
        if (!inImageVars.contains(varName) && !outImageVars.contains(varName)) {
            errorTable.put(varName, ErrorCode.IMAGE_UNUSED);
        }
    }
    
    // check that any vars used in neighbour ref expressions are input
    // image vars
    for (String varName : nbrRefVars) {
        boolean ok = (
            inImageVars.contains(varName) ||
            (imageVars.contains(varName) && !outImageVars.contains(varName))
        );
            
        if (!ok) {
            errorTable.put(varName, ErrorCode.INVALID_NBR_REF);
        }
    }
}

    
}

start
@init {
    if (imageVars == null || imageVars.isEmpty()) {
        throw new RuntimeException("failed to set image vars before using VarClassifier");
    }
}
@after {
    postValidation();
}
                : statement+ 
                ;

statement       : expr
                ;

expr_list       : ^(EXPR_LIST (expr)*)
                ;

expr            : ^(ASSIGN assign_op ID e1=expr)
                  {
                      if (imageVars.contains($ID.text)) {
                          outImageVars.add($ID.text);
                      } else {
                          userVars.add($ID.text);
                      }
                  }

                | ^(FUNC_CALL ID expr_list)
                | ^(NBR_REF ID expr expr)
                  {
                      nbrRefVars.add($ID.text);
                  }

                | ID
                  {
                      if (imageVars.contains($ID.text)) {
                          if (outImageVars.contains($ID.text)) {
                              // error - using image for input and output
                              errorTable.put($ID.text, ErrorCode.IMAGE_IO);
                          } else {
                              inImageVars.add($ID.text);
                          }
                          
                      } else if (!userVars.contains($ID.text) &&
                                 !ConstantLookup.isDefined($ID.text) &&
                                 !unassignedVars.contains($ID.text))
                      {
                          unassignedVars.add($ID.text);
                      }
                  }
                  
                | ^(expr_op expr expr)
                | ^(QUESTION expr expr expr)
                | ^(PREFIX unary_op expr)
                | ^(BRACKETED_EXPR expr)
                | INT_LITERAL 
                | FLOAT_LITERAL 
                | constant
                ;

constant        : TRUE
                | FALSE
                | NULL
                ;

expr_op         : POW
                | TIMES 
                | DIV 
                | MOD
                | PLUS  
                | MINUS
                | OR 
                | AND 
                | XOR 
                | GT 
                | GE 
                | LE 
                | LT 
                | LOGICALEQ 
                | NE 
                ;

assign_op	: EQ
		| TIMESEQ
		| DIVEQ
		| MODEQ
		| PLUSEQ
		| MINUSEQ
		;
		
incdec_op       : INCR
                | DECR
                ;

unary_op	: PLUS
		| MINUS
		| NOT
		;
		
type_name	: 'int'
		| 'float'
		| 'double'
		| 'boolean'
		;

