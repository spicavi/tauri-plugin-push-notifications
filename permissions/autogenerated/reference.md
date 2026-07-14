## Default Permission

Default permissions for the push-notifications plugin.

#### Granted Permissions

- Permission reads and the OS permission prompt (`is_permission_granted`,
  `request_permission`).
- Push registration (`register_for_push`) — returns the device token.
- Notification events (`register_listener`, `remove_listener`,
  `start_notification_events`).

Note: these are Tauri IPC permissions. Delivery is additionally gated by the
OS notification permission — nothing arrives unless the user granted the
system prompt.

#### This default permission set includes the following:

- `allow-is-permission-granted`
- `allow-request-permission`
- `allow-register-for-push`
- `allow-start-notification-events`
- `allow-register-listener`
- `allow-remove-listener`

## Permission Table

<table>
<tr>
<th>Identifier</th>
<th>Description</th>
</tr>


<tr>
<td>

`push-notifications:allow-is-permission-granted`

</td>
<td>

Enables the is_permission_granted command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:deny-is-permission-granted`

</td>
<td>

Denies the is_permission_granted command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:allow-register-for-push`

</td>
<td>

Enables the register_for_push command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:deny-register-for-push`

</td>
<td>

Denies the register_for_push command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:allow-register-listener`

</td>
<td>

Enables the register_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:deny-register-listener`

</td>
<td>

Denies the register_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:allow-remove-listener`

</td>
<td>

Enables the remove_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:deny-remove-listener`

</td>
<td>

Denies the remove_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:allow-request-permission`

</td>
<td>

Enables the request_permission command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:deny-request-permission`

</td>
<td>

Denies the request_permission command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:allow-start-notification-events`

</td>
<td>

Enables the start_notification_events command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`push-notifications:deny-start-notification-events`

</td>
<td>

Denies the start_notification_events command without any pre-configured scope.

</td>
</tr>
</table>
