package com.floww.server.taskaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.task.presentation.TaskInputs;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner JWT only. No caller-supplied amount, recipient, token, task owner, or transaction calldata. */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/account")
public class TaskAccountController {
    private final TaskAccountService service;
    public TaskAccountController(TaskAccountService service) {this.service=service;}
    private static String value(JsonNode body,String key,Set<String> keys) {
        if(body==null || !body.isObject() || body.size()!=keys.size() || !fields(body).equals(keys)
                || !body.path(key).isTextual()) throw new ApiException(ErrorCode.INVALID_INPUT);
        return body.path(key).asText();
    }
    private static Set<String> fields(JsonNode body){Set<String> f=new HashSet<>();body.fieldNames().forEachRemaining(f::add);return f;}
    private static void empty(JsonNode body){if(body!=null && !body.isNull() && (!body.isObject() || body.size()!=0))
        throw new ApiException(ErrorCode.INVALID_INPUT);}
    @GetMapping
    public TaskAccountService.AccountView get(@RequestAttribute(name="owner",required=false) String owner,
                                              @PathVariable UUID taskId) {
        return service.get(TaskInputs.owner(owner),taskId);
    }
    @PostMapping("/prepare")
    public TaskAccountService.AccountView prepare(@RequestAttribute(name="owner",required=false) String owner,
                                                  @PathVariable UUID taskId,@RequestBody JsonNode body) {
        if(body==null || !body.isObject() || !fields(body).equals(Set.of("attemptId","ownerAddress")))
            throw new ApiException(ErrorCode.INVALID_INPUT);
        try {return service.prepare(TaskInputs.owner(owner),taskId,UUID.fromString(body.path("attemptId").asText()),
                value(body,"ownerAddress",Set.of("attemptId","ownerAddress")));}
        catch(IllegalArgumentException e){throw new ApiException(ErrorCode.INVALID_INPUT);}
    }
    @PostMapping("/bind")
    public TaskAccountService.AccountView bind(@RequestAttribute(name="owner",required=false) String owner,
                                               @PathVariable UUID taskId,@RequestBody JsonNode body) {
        if(body==null || !body.isObject() || !fields(body).equals(Set.of("accountAddress","deploymentTxHash")))
            throw new ApiException(ErrorCode.INVALID_INPUT);
        return service.bind(TaskInputs.owner(owner),taskId,value(body,"accountAddress",Set.of("accountAddress","deploymentTxHash")),
                value(body,"deploymentTxHash",Set.of("accountAddress","deploymentTxHash")));
    }
    @PostMapping("/approval-request")
    public TaskAccountService.ApprovalView approval(@RequestAttribute(name="owner",required=false) String owner,
                                                    @PathVariable UUID taskId,@RequestBody(required=false) JsonNode body) {
        empty(body);return service.approvalRequest(TaskInputs.owner(owner),taskId);
    }
    @PostMapping("/signature")
    public TaskAccountService.AccountView signature(@RequestAttribute(name="owner",required=false) String owner,
                                                    @PathVariable UUID taskId,@RequestBody JsonNode body) {
        return service.sign(TaskInputs.owner(owner),taskId,value(body,"signature",Set.of("signature")));
    }
    @PostMapping("/approve")
    public TaskAccountService.AccountView approve(@RequestAttribute(name="owner",required=false) String owner,
                                                  @PathVariable UUID taskId,@RequestBody(required=false) JsonNode body) {
        empty(body);return service.approve(TaskInputs.owner(owner),taskId);
    }
    @GetMapping("/funding")
    public TaskAccountService.FundingView funding(@RequestAttribute(name="owner",required=false) String owner,
                                                  @PathVariable UUID taskId) {
        return service.funding(TaskInputs.owner(owner),taskId);
    }
    @PostMapping("/payment")
    public TaskAccountService.AccountView pay(@RequestAttribute(name="owner",required=false) String owner,
                                              @PathVariable UUID taskId,@RequestBody(required=false) JsonNode body) {
        empty(body);return service.pay(TaskInputs.owner(owner),taskId);
    }
    @PostMapping("/fulfillment")
    public TaskAccountService.AccountView fulfill(@RequestAttribute(name="owner",required=false) String owner,
                                                  @PathVariable UUID taskId,@RequestBody(required=false) JsonNode body) {
        empty(body);return service.fulfill(TaskInputs.owner(owner),taskId);
    }
    @PostMapping("/reconcile")
    public TaskAccountService.AccountView reconcile(@RequestAttribute(name="owner",required=false) String owner,
                                                    @PathVariable UUID taskId,@RequestBody(required=false) JsonNode body) {
        empty(body);return service.reconcile(TaskInputs.owner(owner),taskId);
    }
}
