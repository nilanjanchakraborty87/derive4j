/*
 * Copyright (c) 2015, Jean-Baptiste Giraudeau <jb@giraudeau.info>
 *
 * This file is part of "Derive4J - Annotation Processor".
 *
 * "Derive4J - Annotation Processor" is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * "Derive4J - Annotation Processor" is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with "Derive4J - Annotation Processor".  If not, see <http://www.gnu.org/licenses/>.
 */
package org.derive4j.processor.derivator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.derive4j.processor.Utils;
import org.derive4j.processor.api.DeriveResult;
import org.derive4j.processor.api.DeriveUtils;
import org.derive4j.processor.api.DerivedCodeSpec;
import org.derive4j.processor.api.model.AlgebraicDataType;
import org.derive4j.processor.api.model.DataArgument;
import org.derive4j.processor.api.model.DataArguments;
import org.derive4j.processor.api.model.DataConstructions;
import org.derive4j.processor.api.model.DataConstructor;
import org.derive4j.processor.api.model.DeriveContext;
import org.derive4j.processor.api.model.MultipleConstructorsSupport;
import org.derive4j.processor.api.model.TypeRestriction;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static org.derive4j.processor.Utils.fold;
import static org.derive4j.processor.api.DeriveResult.result;
import static org.derive4j.processor.api.DerivedCodeSpec.methodSpec;

public class CataDerivator {

  private final DeriveUtils utils;

  private final DeriveContext context;

  private final AlgebraicDataType adt;

  public CataDerivator(DeriveUtils utils, DeriveContext context, AlgebraicDataType adt) {
    this.utils = utils;
    this.context = context;
    this.adt = adt;
  }

  public DeriveResult<DerivedCodeSpec> derive() {

    return adt.fields().stream().map(DataArguments::getType).anyMatch(tm -> utils.types().isSameType(tm, adt.typeConstructor().declaredType()))
           ? DataConstructions.cases().multipleConstructors(
       MultipleConstructorsSupport.cases().visitorDispatch(this::visitorDispatchImpl).functionsDispatch(this::functionDispatchImpl))
              .otherwise(() -> result(DerivedCodeSpec.none())).apply(adt.dataConstruction())
           : result(DerivedCodeSpec.none());
  }

  private DeriveResult<DerivedCodeSpec> functionDispatchImpl(List<DataConstructor> constructors) {

    NameAllocator nameAllocator = nameAllocator(constructors);

    TypeElement f = FlavourImpl.findF(context.flavour(), utils.elements());
    TypeName returnType = TypeName.get(utils.types().getDeclaredType(f,
       adt.typeConstructor().declaredType(), adt.matchMethod().returnTypeVariable()));

    TypeSpec wrapper = TypeSpec.anonymousClassBuilder("")
       .addField(FieldSpec.builder(returnType, nameAllocator.get("cata"))
          .initializer(CodeBlock.builder().addStatement("$L -> $L.$L($L)",
             nameAllocator.get("adt var"), nameAllocator.get("adt var"),
             adt.matchMethod().element().getSimpleName(),
             Utils.joinStringsAsArguments(constructors.stream().map(
                constructor ->
                   constructor.arguments().stream()
                      .map(DataArguments::getType)
                      .noneMatch(tm -> utils.types().isSameType(tm, adt.typeConstructor().declaredType()))
                   ? constructor.name()
                   : CodeBlock.builder().add("($L) -> $L.$L($L)", Utils
                         .asLambdaParametersString(constructor.arguments(),
                            constructor.typeRestrictions()), constructor.name(),
                      MapperDerivator.mapperApplyMethod(utils, context, constructor),
                      Utils.joinStringsAsArguments(Stream.concat(
                         constructor.arguments().stream().map(
                            argument ->
                               utils.types().isSameType(argument.type(),
                                  adt.typeConstructor().declaredType())
                               ? "() -> this." + nameAllocator.get("cata")
                                  + "." + FlavourImpl.functionApplyMethod(utils, context)
                                  + "(" + argument.fieldName() + ")"
                               : argument.fieldName()),
                         constructor.typeRestrictions().stream()
                            .map(TypeRestriction::idFunction)
                            .map(DataArgument::fieldName)))).build().toString())

             )).build()).build()).build();

    MethodSpec cataMethod = MethodSpec.methodBuilder("cata")
       .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
       .addTypeVariables(Stream.concat(adt.typeConstructor().typeVariables().stream(), Stream.of(adt.matchMethod().returnTypeVariable()))
          .map(TypeVariableName::get).collect(Collectors.toList()))
       .returns(returnType)
       .addParameters(constructors.stream().map(dc
          -> ParameterSpec.builder(cataMapperTypeName(dc), MapperDerivator.mapperFieldName(dc)).build())
          .collect(toList()))
       .addStatement("return $L.$L", wrapper, nameAllocator.get("cata"))
       .build();

    return result(methodSpec(cataMethod));
  }

  private DeriveResult<DerivedCodeSpec> visitorDispatchImpl(VariableElement visitorParam, DeclaredType visitorType,
     List<DataConstructor> constructors) {

    NameAllocator nameAllocator = nameAllocator(constructors);

    TypeSpec wrapper = TypeSpec.anonymousClassBuilder("")
       .addField(FieldSpec.builder(TypeName.get(visitorType), nameAllocator.get("cata"))
          .initializer(CodeBlock.builder().addStatement("$T.$L($L)",
             ClassName.get(context.targetPackage(), context.targetClassName()),
             MapperDerivator.visitorLambdaFactoryName(adt),
             Utils.joinStringsAsArguments(constructors.stream().map(
                constructor ->
                   constructor.arguments().stream()
                      .map(DataArguments::getType)
                      .noneMatch(tm -> utils.types().isSameType(tm, adt.typeConstructor().declaredType()))
                   ? constructor.name()
                   : CodeBlock.builder().add("($L) -> $L.$L($L)", Utils
                         .asLambdaParametersString(constructor.arguments(),
                            constructor.typeRestrictions()), constructor.name(),
                      MapperDerivator.mapperApplyMethod(utils, context, constructor),
                      Utils.joinStringsAsArguments(Stream.concat(
                         constructor.arguments().stream().map(
                            argument ->
                               utils.types().isSameType(argument.type(),
                                  adt.typeConstructor().declaredType())
                               ? "() -> " + argument.fieldName() + "."
                                  + adt.matchMethod().element().getSimpleName() + "(this."
                                  + nameAllocator.get("cata") + ")"
                               : argument.fieldName()),
                         constructor.typeRestrictions().stream()
                            .map(TypeRestriction::idFunction)
                            .map(DataArgument::fieldName)))).build().toString())

             )).build()).build()).build();

    MethodSpec cataMethod = MethodSpec.methodBuilder("cata")
       .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
       .addTypeVariables(Stream.concat(adt.typeConstructor().typeVariables().stream(), Stream.of(adt.matchMethod().returnTypeVariable()))
          .map(TypeVariableName::get).collect(Collectors.toList()))
       .returns(TypeName.get(utils.types().getDeclaredType(FlavourImpl.findF(context.flavour(), utils.elements()),
          adt.typeConstructor().declaredType(), adt.matchMethod().returnTypeVariable())))
       .addParameters(constructors.stream().map(dc
          -> ParameterSpec.builder(cataMapperTypeName(dc), MapperDerivator.mapperFieldName(dc)).build())
          .collect(toList()))
       .addStatement("$T $L = $L.$L", TypeName.get(visitorType), nameAllocator.get("cata"), wrapper, nameAllocator.get("cata"))
       .addStatement("return $L -> $L.$L($L)", nameAllocator.get("adt var"), nameAllocator.get("adt var"),
          adt.matchMethod().element().getSimpleName(), nameAllocator.get("cata"))
       .build();

    return result(methodSpec(cataMethod));
  }

  private NameAllocator nameAllocator(List<DataConstructor> constructors) {
    NameAllocator nameAllocator = new NameAllocator();
    constructors.stream().forEach(dc ->
       nameAllocator.newName(MapperDerivator.mapperFieldName(dc), MapperDerivator.mapperFieldName(dc) + " arg"));
    nameAllocator.newName("cata", "cata");
    nameAllocator.newName(Utils.uncapitalize(adt.typeConstructor().declaredType().asElement().getSimpleName()), "adt var");
    return nameAllocator;
  }

  TypeName cataMapperTypeName(DataConstructor dc) {

    TypeName[] argsTypeNames = concat(dc.arguments().stream().map(DataArgument::type),
       dc.typeRestrictions().stream().map(TypeRestriction::idFunction).map(DataArgument::type)).map(t -> Utils.asBoxedType.visit(t, utils.types()))
       .map(this::substituteTypeWithRecursionVar)
       .map(TypeName::get)
       .toArray(TypeName[]::new);

    return adt.dataConstruction().isVisitorDispatch()
           ? argsTypeNames.length == 0
             ? ParameterizedTypeName
                .get(ClassName.get(FlavourImpl.findF0(context.flavour(), utils.elements())), TypeName.get(adt.matchMethod().returnTypeVariable()))
             : argsTypeNames.length == 1
               ? ParameterizedTypeName.get(ClassName.get(FlavourImpl.findF(context.flavour(), utils.elements())),
                argsTypeNames[0], TypeName.get(adt.matchMethod().returnTypeVariable()))
               : ParameterizedTypeName.get(Utils.getClassName(context, MapperDerivator.mapperInterfaceName(dc)),
                  concat(concat(
                     dc.typeVariables().stream().map(TypeVariableName::get),
                     fold(MapperDerivator.findInductiveArgument(utils, adt, dc),
                        Stream.<TypeName>of(),
                        tm ->
                           Stream.of(ParameterizedTypeName.get(
                              ClassName.get(FlavourImpl.findF0(context.flavour(), utils.elements())),
                              TypeName.get(adt.matchMethod().returnTypeVariable()))))),
                     Stream.of(TypeVariableName.get(adt.matchMethod().returnTypeVariable())))
                     .toArray(TypeName[]::new))

           : TypeName.get(utils.types().getDeclaredType(Utils.asTypeElement.visit(dc.deconstructor().visitorType().asElement()).get(),
              dc.deconstructor().visitorType().getTypeArguments().stream()
                 .map(this::substituteTypeWithRecursionVar)
                 .toArray(TypeMirror[]::new)));
  }

  TypeMirror substituteTypeWithRecursionVar(TypeMirror tm) {
    return utils.types().isSameType(tm, adt.typeConstructor().declaredType())
           ? utils.types().getDeclaredType(FlavourImpl.findF0(context.flavour(), utils.elements()), adt.matchMethod().returnTypeVariable())
           : tm;
  }
}
