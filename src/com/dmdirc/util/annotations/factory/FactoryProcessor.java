/*
 * Copyright (c) 2006-2014 DMDirc Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.dmdirc.util.annotations.factory;

import com.dmdirc.util.annotations.Constructor;
import com.dmdirc.util.annotations.Parameter;
import com.dmdirc.util.annotations.util.SourceFileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Processor for the {@link Factory} annotation.
 */
@SupportedAnnotationTypes({
    "com.dmdirc.util.annotations.factory.Factory",
    "com.dmdirc.util.annotations.factory.Unbound",})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class FactoryProcessor extends AbstractProcessor {

    /**
     * The fully-qualified names of any elements which are annotated with @Factory but haven't yet
     * been processed. These may persist across several rounds of generation depending on their
     * dependencies.
     */
    private final List<String> pendingElementNames = new LinkedList<>();

    @Override
    public boolean process(final Set<? extends TypeElement> set, final RoundEnvironment roundEnv) {
        pendingElementNames.addAll(getFactoryClassNames(roundEnv));
        final Iterator<String> iterator = pendingElementNames.iterator();
        while (iterator.hasNext()) {
            // Because we're possibly caching these names across rounds we need to look up each
            // element from the environment instead of caching.
            final String name = iterator.next();
            final Element type = processingEnv.getElementUtils().getTypeElement(name);
            Factory annotation = type.getAnnotation(Factory.class);

            if (annotation == null) {
                continue;
            }

            if (!(type instanceof TypeElement)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Factory annotation can only be applied to classes", type);
                continue;
            }

            final TypeElement typeElement = (TypeElement) type;
            final Element enclosingElement = type.getEnclosingElement();
            if (!(enclosingElement instanceof PackageElement)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Factory annotation must be applied to outer-classes", enclosingElement);
                continue;
            }

            final PackageElement packageElement = (PackageElement) enclosingElement;

            final List<Parameter> boundParameters = new ArrayList<>();
            final List<Constructor> constructors = new ArrayList<>();
            boolean errorFree = true;

            for (Element child : type.getEnclosedElements()) {
                if (child.getKind() == ElementKind.CONSTRUCTOR) {
                    final List<Parameter> params = new ArrayList<>();

                    ExecutableElement ctor = (ExecutableElement) child;
                    for (VariableElement element : ctor.getParameters()) {
                        if (element.asType().getKind() == TypeKind.ERROR) {
                            // Either something bad has happened, or this is the output of an
                            // annotation processor that's not run yet. Either way, avoid generating
                            // any bad output.
                            errorFree = false;
                            break;
                        }

                        final Parameter param = new Parameter(
                                element.asType().toString(),
                                element.getSimpleName().toString(),
                                getAnnotations(element));
                        if (element.getAnnotation(Unbound.class) == null) {
                            if (!boundParameters.contains(param)) {
                                boundParameters.add(param);
                            }
                        }

                        params.add(param);
                    }

                    constructors.add(new Constructor(params, getTypeNames(ctor.getThrownTypes())));
                }
            }

            if (errorFree) {
                writeFactory(
                        packageElement.getQualifiedName().toString(),
                        annotation.name().isEmpty()
                        ? typeElement.getSimpleName() + "Factory"
                        : annotation.name(),
                        typeElement.getSimpleName().toString(),
                        annotation,
                        boundParameters,
                        constructors,
                        type);
                iterator.remove();
            }
        }

        return false;
    }

    /**
     * Gets the fully-qualified names of all classes in the given environment that are annotated
     * with our @Factory annotation.
     *
     * @param roundEnv The environment to read elements from
     * @return A set of all class names that are annotated
     */
    private Set<String> getFactoryClassNames(final RoundEnvironment roundEnv) {
        final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Factory.class);
        final Set<String> names = new HashSet<>(elements.size());
        for (Element element : elements) {
            final Element enclosingElement = element.getEnclosingElement();
            if (enclosingElement instanceof PackageElement) {
                final PackageElement packageElement = (PackageElement) enclosingElement;
                final String packageName = packageElement.getQualifiedName().toString();
                names.add(packageName + '.' + element.getSimpleName().toString());
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Factory annotation must be applied to outer-classes", enclosingElement);
            }
        }
        return names;
    }

    /**
     * Writes all of the given parameters as method parameters.
     *
     * @param annotation The annotation configuring the factory. {@code null} to use defaults.
     * @param writer The writer to write to.
     * @param parameters The parameters to be written.
     * @throws IOException If the operation failed.
     */
    private void writeMethodParameters(
            final Factory annotation,
            final SourceFileWriter writer,
            final List<Parameter> parameters) throws IOException {
        for (Parameter param : parameters) {
            writer.writeMethodParameter(
                    param.getAnnotations(),
                    maybeWrapProvider(annotation, param),
                    param.getName(),
                    Modifier.FINAL);
        }
    }

    /**
     * Gets all the annotations (except the ones declared by this library) of the given element as a
     * string.
     *
     * @param element The element to retrieve annotations for.
     * @return A space-separated string of all annotations and their values.
     */
    private String getAnnotations(final Element element) {
        final StringBuilder builder = new StringBuilder();

        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().startsWith("com.dmdirc.util.annotations")) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(mirror);
        }

        return builder.toString();
    }

    /**
     * Writes out a factory class as a source file.
     *
     * @param packageName The package to put the factory in.
     * @param factoryName The simple name of the factory.
     * @param typeName The simple name of the class being built.
     * @param annotation The annotation configuring the factory.
     * @param boundParameters The parameters bound by the factory (required by the constructor).
     * @param constructors A list of constructors.
     * @param elements The element(s) responsible for the file being written.
     */
    private void writeFactory(final String packageName, final String factoryName,
            final String typeName, final Factory annotation, final List<Parameter> boundParameters,
            final List<Constructor> constructors, final Element... elements) {
        try (SourceFileWriter writer = new SourceFileWriter(processingEnv.getFiler(),
                packageName + (packageName.isEmpty() ? "" : ".") + factoryName, elements)) {
            final String methodName = "get" + typeName;

            writer.writePackageDeclaration(packageName)
                    .writeAnnotationIf("@javax.inject.Singleton", annotation.singleton())
                    .writeClassDeclaration(factoryName, getClass(), annotation.modifiers());

            // All the fields we need
            for (Parameter boundParam : boundParameters) {
                writer.writeField(
                        maybeWrapProvider(annotation, boundParam),
                        boundParam.getName(),
                        Modifier.PRIVATE, Modifier.FINAL);
            }

            // Constructor declaration
            writer.writeAnnotationIf("@javax.inject.Inject", annotation.inject())
                    .writeConstructorDeclarationStart(factoryName, Modifier.PUBLIC);
            writeMethodParameters(annotation, writer, boundParameters);
            writer.writeMethodDeclarationEnd();

            // Assign the values to fields
            for (Parameter boundParam : boundParameters) {
                writer.writeFieldAssignment(boundParam.getName(), boundParam.getName());
            }

            // End of constructor
            writer.writeBlockEnd();

            // Write each factory method out in turn
            for (Constructor constructor : constructors) {
                final List<Parameter> params = constructor.getParameters();
                final List<Parameter> unbound = new ArrayList<>(params);
                unbound.removeAll(boundParameters);

                final String[] parameters = new String[params.size()];
                for (int i = 0; i < parameters.length; i++) {
                    if (annotation.providers()
                            && !params.get(i).getType().startsWith("javax.inject.Provider")
                            && boundParameters.contains(params.get(i))
                            && params.get(i).getAnnotations().isEmpty()) {
                        parameters[i] = params.get(i).getName() + ".get()";
                    } else {
                        parameters[i] = params.get(i).getName();
                    }
                }

                final String[] throwsArray = constructor.getThrownTypes()
                        .toArray(new String[constructor.getThrownTypes().size()]);

                writer.writeMethodDeclarationStart(typeName, methodName, annotation.methodModifiers());
                writeMethodParameters(null, writer, unbound);
                writer.writeMethodDeclarationEnd(throwsArray)
                        .writeReturnStart()
                        .writeNewInstance(typeName, parameters)
                        .writeStatementEnd()
                        .writeBlockEnd();
            }

            // Done!
            writer.writeBlockEnd();
        } catch (IOException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Unable to write factory file: " + ex.getMessage());
        }
    }

    /**
     * Writes the given type in a Provider&lt;&gt;, if the factory is configured to use them, if the
     * type is not already a provider, and if the parameter has no annotations.
     *
     * @param annotation The annotation configuring the factory.
     * @param parameter The parameter to possibly wrap.
     * @return The possibly-wrapped type.
     */
    private String maybeWrapProvider(final Factory annotation, final Parameter parameter) {
        if (annotation != null
                && annotation.providers()
                && !parameter.getType().startsWith("javax.inject.Provider")
                && parameter.getAnnotations().isEmpty()) {
            return "javax.inject.Provider<" + parameter.getType() + ">";
        } else {
            return parameter.getType();
        }
    }

    /**
     * Gets a list of fully-qualified type names corresponding to the given mirrors.
     *
     * @param types The set of types to convert to qualified names.
     * @return A matching list containing the qualified name of each type.
     */
    private List<String> getTypeNames(final List<? extends TypeMirror> types) {
        final List<String> res = new ArrayList<>(types.size());

        for (TypeMirror type : types) {
            res.add(type.toString());
        }

        return res;
    }

}
