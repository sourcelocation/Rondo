// Kratos's messages as Rondo's mail sender takes them: it renders them with Rondo's own templates.
local data(ctx) = if 'template_data' in ctx && ctx.template_data != null then ctx.template_data else {};
local first(d, keys) = std.foldl(function(found, k) if found != '' then found else std.toString(std.get(d, k, '')), keys, '');

function(ctx) {
  template_type: ctx.template_type,
  recipient: ctx.recipient,
  code: first(data(ctx), ['login_code', 'registration_code', 'verification_code', 'recovery_code']),
  url: first(data(ctx), ['verification_url', 'recovery_url']),
}
