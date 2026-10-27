package io.stackvibed.harness

import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification

/** RestAssured filter that adds [StackContext.headers] to every request. */
class StackBaggageFilter : Filter {
    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        StackContext.headers().forEach { (name, value) -> requestSpec.header(name, value) }
        return ctx.next(requestSpec, responseSpec)
    }
}
