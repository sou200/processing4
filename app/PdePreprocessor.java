/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  PdePreprocessor - default ANTLR-generated parser
  Part of the Processing project - http://Proce55ing.net

  Copyright (c) 2001-03 
  Ben Fry, Massachusetts Institute of Technology and 
  Casey Reas, Interaction Design Institute Ivrea

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License 
  along with this program; if not, write to the Free Software Foundation, 
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

import java.io.*;
import antlr.*;
import antlr.collections.*;
import antlr.collections.impl.*;

public class PdePreprocessor {

  static final String applet_imports[] = {
    "java.applet", "java.awt", "java.awt.image", "java.awt.event",
    "java.io", "java.net", "java.text", "java.util", "java.util.zip"
  };

  static final String application_imports[] = {
    "java.applet", "java.awt", "java.awt.image", "java.awt.event",
    "java.io", "java.net", "java.text", "java.util", "java.util.zip",
#ifndef RXTX
    "javax.comm",
#else
    "gnu.io",
#endif

    // if jdk14 defined, jdk13 will be as well
#ifdef JDK13
    "javax.sound.midi", "javax.sound.midi.spi",
    "javax.sound.sampled", "javax.sound.sampled.spi",
#endif

#ifdef JDK14
    "javax.xml.parsers", "javax.xml.transform", 
    "javax.xml.transform.dom", "javax.xml.transform.sax",
    "javax.xml.transform.stream", "org.xml.sax",
    "org.xml.sax.ext", "org.xml.sax.helpers"
#endif
  };

  static final int BEGINNER     = 0;
  static final int INTERMEDIATE = 1;
  static final int ADVANCED     = 2;

  static int programType = -1;

  String tempClass;
  String tempFilename;
  String tempClassFilename;

  Reader programReader;
  String buildPath;

  // used for calling the ASTFactory to get the root node
  private static final int ROOT_ID = 0;

  boolean usingExternal; // use an external process to display the applet?

  public PdePreprocessor(String program, String buildPath) {
    this.programReader = new StringReader(program);
    this.buildPath = buildPath;

    usingExternal = PdeBase.getBoolean("play.external", false);
  }

  /**
   * Used by PdeEmitter.dumpHiddenTokens()
   */
  public static TokenStreamCopyingHiddenTokenFilter filter;

  /**
   * preprocesses a pde file and write out a java file
   *
   * @return the classname of the exported Java
   */
  public String writeJava(String name, boolean extendsNormal,
                          boolean exporting) throws java.lang.Exception {

    // create a lexer with the stream reader, and tell it to handle 
    // hidden tokens (eg whitespace, comments) since we want to pass these
    // through so that the line numbers when the compiler reports errors
    // match those that will be highlighted in the PDE IDE
    // 
    PdeLexer lexer  = new PdeLexer(programReader);
    lexer.setTokenObjectClass("antlr.CommonHiddenStreamToken");

    // create the filter for hidden tokens and specify which tokens to 
    // hide and which to copy to the hidden text
    //
    filter = new TokenStreamCopyingHiddenTokenFilter(lexer);
    filter.hide(PdeRecognizer.SL_COMMENT);
    filter.hide(PdeRecognizer.ML_COMMENT);
    filter.hide(PdeRecognizer.WS);
    filter.copy(PdeRecognizer.SEMI);
    filter.copy(PdeRecognizer.LPAREN);
    filter.copy(PdeRecognizer.RPAREN);
    filter.copy(PdeRecognizer.LCURLY);
    filter.copy(PdeRecognizer.RCURLY);
    filter.copy(PdeRecognizer.COMMA);
    filter.copy(PdeRecognizer.RBRACK);
    filter.copy(PdeRecognizer.LBRACK);
    filter.copy(PdeRecognizer.COLON);

    // create a parser and set what sort of AST should be generated
    //
    PdeRecognizer parser = new PdeRecognizer(filter);

    // XXXdmose comments and stuff
    parser.setASTNodeClass("antlr.ExtendedCommonASTWithHiddenTokens");

    // start parsing at the compilationUnit non-terminal
    //
    try {
      parser.pdeProgram();
    } catch (Exception e) {
      System.err.println("exception: " + e);
    }

    // set up the AST for traversal by PdeEmitter
    //
    ASTFactory factory = new ASTFactory();
    AST parserAST = parser.getAST();
    AST rootNode = factory.create(ROOT_ID, "AST ROOT");
    rootNode.setFirstChild(parserAST);

    // unclear if this actually works, but it's worth a shot
    //
    ((CommonAST)parserAST).setVerboseStringConversion(
      true, parser.getTokenNames());

    // if this is an advanced program, the classname is already defined.
    //
    if ( programType == ADVANCED ) {
      name = getFirstClassName(parserAST);
    }

    // output the code
    //
    PdeEmitter emitter = new PdeEmitter();
    PrintStream stream = new PrintStream(
      new FileOutputStream(buildPath + File.separator + name + ".java"));

    writeHeader(stream, extendsNormal, exporting, name);

    emitter.setOut(stream);
    // XXXdmose should try block encompass more?
    emitter.print(rootNode);

    writeFooter(stream);
    stream.close();

    final boolean debug = true;

    // if we're debugging, serialize the parse tree to an XML file.  can
    // be viewed usefully with Mozilla or IE

    if (debug) {

      stream = new PrintStream(new FileOutputStream("parseTree.xml"));
      stream.println("<?xml version=\"1.0\"?>");
      stream.println("<document>");
      OutputStreamWriter writer = new OutputStreamWriter(stream);
      ((CommonAST)parserAST).xmlSerialize(writer);
      writer.flush();
      stream.println("</document>");
      writer.close();
    }

    return name;
  }

  /**
   * Write any required header material (eg imports, class decl stuff)
   * 
   * @param out                 PrintStream to write it to.
   * @param extendsNormal       Extending the normal renderer?
   * @param exporting           Is this being exported from PDE?
   * @param name                Name of the class being created.
   */
  void writeHeader(PrintStream out, boolean extendsNormal, boolean exporting,
                   String name) {

    if (programType < ADVANCED) {

      String extendsWhat = extendsNormal ? "BApplet" : "BAppletGL";

      // spew out a bunch of java imports 
      //
      if (!exporting) {  // if running in environment, or exporting an app
        for (int i = 0; i < application_imports.length; i++) {
          out.print("import " + application_imports[i] + ".*; ");
        }
      } else {  // exporting an applet
        for (int i = 0; i < applet_imports.length; i++) {
          out.print("import " + applet_imports[i] + ".*; ");
        }
      }

      out.print("public class " + name + " extends " +
                extendsWhat + " {");
    }
  }

  /**
   * Write any necessary closing text.
   * 
   * @param out         PrintStream to write it to.
   */
  void writeFooter(PrintStream out) {
    if (programType < ADVANCED) {
      // close off the class definition
      out.print("}");
    }
  }

  /**
   * Find the first CLASS_DEF node in the tree, and return the name of the
   * class in question.
   */
  String getFirstClassName(AST ast) {

    // XXXdmose actually do the find
    return "MyDemo";
  }

}
