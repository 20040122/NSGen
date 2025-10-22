
package org.example.tm.generator

import org.eclipse.emf.ecore.resource.Resource
import org.eclipse.xtext.generator.AbstractGenerator
import org.eclipse.xtext.generator.IFileSystemAccess2
import org.eclipse.xtext.generator.IGeneratorContext
import org.example.tm.tM.Model
import org.example.tm.tM.Module
import org.example.tm.tM.Attribute
import org.example.tm.tM.Value
import org.example.tm.tM.Ref

class TMGenerator extends AbstractGenerator {

    override void doGenerate(Resource resource, IFileSystemAccess2 fsa, IGeneratorContext context) {
        if (resource === null || resource.contents.empty) return

        val model = resource.contents.head as Model

        val jsonItems = model.modules.map[toJsonObject]
        val json = '''
        [
        «FOR it : jsonItems SEPARATOR ",\n"»«it»«ENDFOR»
        ]
        '''.toString

        val outName = resource.URI.trimFileExtension.lastSegment + ".json"
        fsa.generateFile(outName, IFileSystemAccess2.DEFAULT_OUTPUT, json)
    }

    def private toJsonObject(Module m) {
        val rhs = buildRhs(m)
        '''{"name":"«escape(m.name)»","val":"«escape(rhs)»"}'''
    }

    def private buildRhs(Module m) {
        val typePart  = "&" + m.type.name
        val attrsPart = if (m.attrs === null || m.attrs.empty) ""
                        else " " + m.attrs.map[aToText].join(", ")
        typePart + attrsPart + " /"
    }

    def private aToText(Attribute a) {
        if (a.value === null) a.name
        else a.name + "=" + vToText(a.value)
    }

    def private vToText(Value v) {
        if (v.v) return '[[V]]'                  
        if (v.c) return '[[C]]'
        if (v.ref !== null) return refToText(v.ref)
        return ""                               
    }

    def private refToText(Ref r) {
        '<' + r.module + '.' + r.key + '>'
    }

    def private escape(String s) {
        if (s === null) return ""
        s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

